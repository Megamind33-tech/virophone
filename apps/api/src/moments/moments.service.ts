import { BadRequestException, ConflictException, ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { RealtimeRegistry } from '../realtime/realtime.registry';
import { PushService } from '../push/push.service';
import { publicAvatarUrl } from '../users/avatar.util';

export const MOMENT_TYPES = ['FREE', 'BREAK', 'LISTENING', 'WATCHING', 'GAMING', 'WORKING', 'CUSTOM'];
export const MOMENT_AUDIENCES = ['CONNECTIONS', 'CONTACTS'];
export const MOMENT_REACTIONS = ['❤️', '😂', '🔥', '👏', '👍'];
export interface CreateMoment { type: string; text?: string; visibility: string; durationMinutes: number }

// Contacts is deliberately owner-directed: knowing somebody's number does not
// entitle a stranger to their activity. Every read rechecks blocks both ways.
const VISIBLE = `(m.creator_user_id = $1 OR (
 NOT EXISTS (SELECT 1 FROM blocks b WHERE
   (b.blocker_user_id = $1 AND b.blocked_user_id = m.creator_user_id) OR
   (b.blocker_user_id = m.creator_user_id AND b.blocked_user_id = $1))
 AND ((m.visibility = 'CONNECTIONS' AND EXISTS (SELECT 1 FROM viro_connections c
   WHERE c.status = 'ACCEPTED' AND ((c.requester_user_id = $1 AND c.recipient_user_id = m.creator_user_id)
   OR (c.recipient_user_id = $1 AND c.requester_user_id = m.creator_user_id))))
 OR (m.visibility = 'CONTACTS' AND EXISTS (SELECT 1 FROM contact_matches c
   WHERE c.user_id = m.creator_user_id AND c.matched_user_id = $1 AND c.expires_at > now())))))`;

@Injectable()
export class MomentsService {
  constructor(
    private readonly db: DataSource,
    private readonly realtime: RealtimeRegistry,
    private readonly push: PushService,
  ) {}

  async now(userId: string) {
    const rows = await this.db.query(`SELECT m.*, p.display_name,
      CASE WHEN m.creator_user_id = $1 OR p.photo_visibility = 'EVERYONE' OR
        (p.photo_visibility = 'CONTACTS' AND (EXISTS (SELECT 1 FROM contact_matches c
          WHERE c.user_id = $1 AND c.matched_user_id = m.creator_user_id AND c.expires_at > now())
        OR EXISTS (SELECT 1 FROM viro_connections c WHERE c.status = 'ACCEPTED' AND
          ((c.requester_user_id = $1 AND c.recipient_user_id = m.creator_user_id) OR
           (c.recipient_user_id = $1 AND c.requester_user_id = m.creator_user_id)))))
        THEN p.avatar_url ELSE NULL END AS visible_avatar,
      (SELECT count(*)::int FROM moment_participants mp WHERE mp.moment_id = m.id) AS participant_count
      FROM moments m LEFT JOIN profiles p ON p.user_id = m.creator_user_id
      WHERE m.status = 'ACTIVE' AND m.expires_at > now() AND ${VISIBLE}
      ORDER BY m.created_at DESC`, [userId]);
    return { serverTime: new Date().toISOString(), moments: rows.map((r: any) => this.dto(r)) };
  }

  async get(userId: string, id: string) {
    // Same response for nonexistent, expired, ended and unauthorized identifiers.
    const row = (await this.now(userId)).moments.find((m: any) => m.id === id);
    if (!row) throw new NotFoundException('Moment unavailable.');
    return row;
  }

  async create(userId: string, body: CreateMoment) {
    await this.sweep();
    let row: any;
    try {
      [row] = await this.db.query(`INSERT INTO moments (creator_user_id,type,text,visibility,expires_at)
        VALUES ($1,$2,$3,$4,now() + $5 * interval '1 minute') RETURNING *`,
      [userId, body.type, body.text?.trim() || null, body.visibility, body.durationMinutes]);
    } catch (e) {
      if ((e as { code?: string }).code === '23505') throw new ConflictException('You already have an active Moment.');
      throw e;
    }
    // The host is in their own room from the moment it exists; the People list
    // and every participant count start at one without a separate join call.
    await this.db.query(`INSERT INTO moment_participants (moment_id, user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, [row.id, userId]);
    await this.notify(row, 'moment.created');
    return this.get(userId, row.id);
  }

  async extend(userId: string, id: string, minutes: number) {
    const [rows] = await this.db.query(`UPDATE moments SET expires_at = LEAST(
      expires_at + $3 * interval '1 minute', created_at + interval '2 hours')
      WHERE id = $2 AND creator_user_id = $1 AND status = 'ACTIVE' AND expires_at > now() RETURNING *`,
    [userId, id, minutes]);
    const row = rows[0];
    if (!row) throw new NotFoundException('Moment unavailable.');
    await this.notify(row, 'moment.updated');
    return this.get(userId, id);
  }

  async end(userId: string, id: string) {
    const [rows] = await this.db.query(`UPDATE moments SET status = 'ENDED'
      WHERE id = $2 AND creator_user_id = $1 AND status = 'ACTIVE' AND expires_at > now() RETURNING *`, [userId, id]);
    const row = rows[0];
    if (!row) throw new NotFoundException('Moment unavailable.');
    await this.closeRoom(row, 'moment.ended');
    return { success: true };
  }

  async sweep() {
    // Claim atomically across replicas. Reads reject expired rows even if a
    // worker was stopped; restarting cannot revive an expired Moment.
    const rows = await this.db.query(`UPDATE moments SET status = 'EXPIRED'
      WHERE status = 'ACTIVE' AND expires_at <= now() RETURNING *`);
    for (const row of rows[0] ?? []) await this.closeRoom(row, 'moment.expired');
  }

  // ------------------------------------------------------------------ rooms

  /** Room state is a fact about membership, not about the list: a blocked or
   * disconnected user keeps nothing. Every room read re-derives visibility. */
  private async liveMoment(userId: string, id: string) {
    await this.sweep();
    const [row] = await this.db.query(`SELECT m.* FROM moments m
      WHERE m.id = $2 AND m.status = 'ACTIVE' AND m.expires_at > now() AND ${VISIBLE}`, [userId, id]);
    if (!row) throw new NotFoundException('Moment unavailable.');
    return row;
  }

  private async isParticipant(momentId: string, userId: string) {
    const [row] = await this.db.query(`SELECT 1 FROM moment_participants WHERE moment_id = $1 AND user_id = $2`, [momentId, userId]);
    return !!row;
  }

  async join(userId: string, id: string) {
    const moment = await this.liveMoment(userId, id);
    // Idempotent: reconnecting or re-tapping Join cannot duplicate a row or a
    // second "joined" announcement to people already in the room.
    const inserted = this.rowsOf(await this.db.query(`INSERT INTO moment_participants (moment_id, user_id)
      VALUES ($1,$2) ON CONFLICT DO NOTHING RETURNING user_id`, [id, userId]));
    if (inserted.some((r) => r.user_id === userId)) {
      await this.announceRoom(id, 'moment.joined', { momentId: id, userId, displayName: await this.nameOf(userId) });
    }
    return this.room(userId, id, moment);
  }

  async leave(userId: string, id: string) {
    const hosts = this.rowsOf(await this.db.query(`SELECT 1 AS one FROM moments WHERE id = $2 AND creator_user_id = $1`, [userId, id]));
    if (hosts.length > 0) throw new BadRequestException('End your Moment instead of leaving it.');
    const removed = this.rowsOf(await this.db.query(`DELETE FROM moment_participants WHERE moment_id = $1 AND user_id = $2 RETURNING user_id`, [id, userId]));
    if (removed.length === 0) throw new NotFoundException('You are not in this room.');
    await this.announceRoom(id, 'moment.left', { momentId: id, userId });
    return { success: true };
  }

  async room(userId: string, id: string, preloaded?: any) {
    const moment = preloaded ?? await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) {
      // Guests see rooms by being in them; the host is a participant from creation.
      throw new ForbiddenException('Join this Moment to see its room.');
    }
    const participants = await this.db.query(`SELECT mp.user_id, mp.joined_at, p.display_name,
      (mp.user_id = $2) AS is_host FROM moment_participants mp
      LEFT JOIN profiles p ON p.user_id = mp.user_id WHERE mp.moment_id = $1 ORDER BY mp.joined_at`, [id, moment.creator_user_id]);
    const messages = await this.db.query(`SELECT mm.*, p.display_name FROM moment_messages mm
      LEFT JOIN profiles p ON p.user_id = mm.sender_user_id
      WHERE mm.moment_id = $1 ORDER BY mm.created_at DESC LIMIT 100`, [id]);
    const reactions = await this.db.query(`SELECT r.message_id, r.user_id, r.emoji FROM moment_reactions r
      JOIN moment_messages mm ON mm.id = r.message_id WHERE mm.moment_id = $1`, [id]);
    const byMessage = new Map<string, { emoji: string; userIds: string[] }[]>();
    for (const r of reactions) {
      const list = byMessage.get(r.message_id) ?? [];
      const entry = list.find((e) => e.emoji === r.emoji);
      if (entry) entry.userIds.push(r.user_id); else list.push({ emoji: r.emoji, userIds: [r.user_id] });
      byMessage.set(r.message_id, list);
    }
    const viewer = await this.get(userId, id);
    return {
      moment: viewer,
      serverTime: new Date().toISOString(),
      participants: participants.map((p: any) => ({
        userId: p.user_id, displayName: p.display_name || 'Viro user', isHost: p.is_host,
        joinedAt: new Date(p.joined_at).toISOString(),
      })),
      messages: messages.reverse().map((m: any) => ({
        id: m.id, momentId: m.moment_id, senderUserId: m.sender_user_id,
        senderName: m.display_name || 'Viro user', body: m.body,
        createdAt: new Date(m.created_at).toISOString(),
        reactions: byMessage.get(m.id) ?? [],
      })),
    };
  }

  async message(userId: string, id: string, body: string) {
    const moment = await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment to chat.');
    const text = body.trim().slice(0, 500);
    if (!text) throw new BadRequestException('Message is empty.');
    const [row] = await this.db.query(`INSERT INTO moment_messages (moment_id, sender_user_id, body)
      VALUES ($1,$2,$3) RETURNING *`, [id, userId, text]);
    const dto = { id: row.id, momentId: id, senderUserId: userId, senderName: await this.nameOf(userId),
      body: row.body, createdAt: new Date(row.created_at).toISOString(), reactions: [] };
    await this.announceRoom(id, 'moment.message', { momentId: id, message: dto });
    return dto;
  }

  async react(userId: string, id: string, messageId: string, emoji: string | null) {
    const moment = await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment to react.');
    if (emoji !== null && !MOMENT_REACTIONS.includes(emoji)) throw new BadRequestException('Unsupported reaction.');
    const [target] = await this.db.query(`SELECT 1 FROM moment_messages WHERE id = $1 AND moment_id = $2`, [messageId, id]);
    if (!target) throw new NotFoundException('Message unavailable.');
    if (emoji === null) {
      await this.db.query(`DELETE FROM moment_reactions WHERE message_id = $1 AND user_id = $2`, [messageId, userId]);
    } else {
      await this.db.query(`INSERT INTO moment_reactions (message_id, user_id, emoji) VALUES ($1,$2,$3)
        ON CONFLICT (message_id, user_id) DO UPDATE SET emoji = EXCLUDED.emoji`, [messageId, userId, emoji]);
    }
    await this.announceRoom(id, 'moment.reaction', { momentId: id, messageId, userId, emoji });
    return { success: true };
  }

  // ------------------------------------------------------------------ knocks

  async knock(userId: string, id: string) {
    const moment = await this.liveMoment(userId, id);
    if (moment.creator_user_id === userId) throw new BadRequestException('You host this Moment.');
    if (moment.type !== 'FREE') throw new BadRequestException('This Moment is not open to calls.');
    if (await this.isParticipant(id, userId)) throw new BadRequestException('You are already in this room.');
    await this.db.query(`INSERT INTO moment_knocks (moment_id, knocker_user_id) VALUES ($1,$2)
      ON CONFLICT (moment_id, knocker_user_id) DO UPDATE SET status = 'PENDING' RETURNING id`, [id, userId]);
    const name = await this.nameOf(userId);
    const delivered = await this.realtime.deliverToUser(moment.creator_user_id, {
      type: 'moment.knock', payload: { momentId: id, knockerUserId: userId, knockerName: name } });
    if (!delivered) {
      await this.push.sendToUser(moment.creator_user_id, {
        title: `${name} wants to talk`,
        body: 'Tap to answer from their Moment.',
        data: { type: 'moment-knock', momentId: id, knockerUserId: userId },
      });
    }
    return { success: true };
  }

  async knocks(userId: string, id: string) {
    const [moment] = await this.db.query(`SELECT 1 FROM moments WHERE id = $2 AND creator_user_id = $1 AND status = 'ACTIVE'`, [userId, id]);
    if (!moment) throw new NotFoundException('Moment unavailable.');
    const rows = await this.db.query(`SELECT k.knocker_user_id, k.created_at, p.display_name
      FROM moment_knocks k LEFT JOIN profiles p ON p.user_id = k.knocker_user_id
      WHERE k.moment_id = $1 AND k.status = 'PENDING' ORDER BY k.created_at DESC`, [id]);
    return { knocks: rows.map((k: any) => ({ knockerUserId: k.knocker_user_id,
      knockerName: k.display_name || 'Viro user', createdAt: new Date(k.created_at).toISOString() })) };
  }

  async respondToKnock(userId: string, id: string, knockerId: string, accept: boolean) {
    const [moment] = await this.db.query(`SELECT 1 AS one FROM moments WHERE id = $2 AND creator_user_id = $1`, [userId, id]);
    if (!moment) throw new NotFoundException('Moment unavailable.');
    const updated = this.rowsOf(await this.db.query(`UPDATE moment_knocks SET status = $3
      WHERE moment_id = $1 AND knocker_user_id = $2 AND status = 'PENDING' RETURNING id`, [id, knockerId, accept ? 'ACCEPTED' : 'DISMISSED']));
    if (updated.length === 0) throw new NotFoundException('No pending knock from this person.');
    return { success: true };
  }

  // -------------------------------------------------------------- invitations

  async invite(userId: string, id: string, inviteeId: string) {
    const moment = await this.liveMoment(userId, id);
    if (moment.creator_user_id !== userId) throw new ForbiddenException('Only the host can invite.');
    if (inviteeId === userId) throw new BadRequestException('You host this Moment.');
    const [blocked] = await this.db.query(`SELECT 1 FROM blocks WHERE
      (blocker_user_id = $1 AND blocked_user_id = $2) OR (blocker_user_id = $2 AND blocked_user_id = $1)`, [userId, inviteeId]);
    if (blocked) throw new NotFoundException('Connection unavailable.');
    const [connected] = await this.db.query(`SELECT 1 FROM viro_connections WHERE status = 'ACCEPTED' AND
      ((requester_user_id = $1 AND recipient_user_id = $2) OR (recipient_user_id = $1 AND requester_user_id = $2))`, [userId, inviteeId]);
    if (!connected) throw new BadRequestException('You can only invite accepted Viro connections.');
    await this.db.query(`INSERT INTO moment_invitations (moment_id, invitee_user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, [id, inviteeId]);
    const name = await this.nameOf(userId);
    const activity = moment.text?.trim() || moment.type;
    const delivered = await this.realtime.deliverToUser(inviteeId, {
      type: 'moment.invited', payload: { momentId: id, inviterName: name, activity } });
    if (!delivered) {
      await this.push.sendToUser(inviteeId, {
        title: `${name} invited you to a Moment`,
        body: activity,
        data: { type: 'moment-invite', momentId: id },
      });
    }
    return { success: true };
  }

  /** Pending invitations to Moments that are still live and still visible to
   * the invitee — a later block or the Moment ending removes them from this
   * list without anyone having to act on them. */
  async invitations(userId: string) {
    const rows = await this.db.query(`SELECT i.id AS invitation_id, i.created_at AS invited_at, m.*, p.display_name,
      CASE WHEN p.photo_visibility = 'EVERYONE' OR
        (p.photo_visibility = 'CONTACTS' AND (EXISTS (SELECT 1 FROM contact_matches c
          WHERE c.user_id = $1 AND c.matched_user_id = m.creator_user_id AND c.expires_at > now())
        OR EXISTS (SELECT 1 FROM viro_connections c WHERE c.status = 'ACCEPTED' AND
          ((c.requester_user_id = $1 AND c.recipient_user_id = m.creator_user_id) OR
           (c.recipient_user_id = $1 AND c.requester_user_id = m.creator_user_id)))))
        THEN p.avatar_url ELSE NULL END AS visible_avatar
      FROM moment_invitations i
      JOIN moments m ON m.id = i.moment_id
      LEFT JOIN profiles p ON p.user_id = m.creator_user_id
      WHERE i.invitee_user_id = $1 AND m.status = 'ACTIVE' AND m.expires_at > now() AND ${VISIBLE}
      ORDER BY i.created_at DESC`, [userId]);
    return { invitations: rows.map((r: any) => ({ invitationId: r.invitation_id,
      invitedAt: new Date(r.invited_at).toISOString(), moment: this.dto(r) })) };
  }

  async declineInvitation(userId: string, invitationId: string) {
    const removed = this.rowsOf(await this.db.query(`DELETE FROM moment_invitations WHERE id = $1 AND invitee_user_id = $2 RETURNING id`, [invitationId, userId]));
    if (removed.length === 0) throw new NotFoundException('Invitation unavailable.');
    return { success: true };
  }

  // ------------------------------------------------------------------ shared

  /** Sends a frame to everyone currently in the room (host included). */
  private async announceRoom(momentId: string, type: string, payload: Record<string, unknown>) {
    const rows = await this.db.query(`SELECT user_id FROM moment_participants WHERE moment_id = $1`, [momentId]);
    await Promise.allSettled(rows.map((r: any) => this.realtime.deliverToUser(r.user_id, { type, payload })));
  }

  // TypeORM hands back UPDATE/DELETE ... RETURNING as [rows, affectedCount]
  // but SELECT/INSERT as plain rows (see keys.service consumePrekey). Moments
  // never needs the count itself, so both shapes collapse to the row list.
  private rowsOf(result: unknown): any[] {
    const r = result as any;
    return Array.isArray(r) && r.length === 2 && Array.isArray(r[0]) && typeof r[1] === 'number'
      ? r[0]
      : Array.isArray(r) ? r : [];
  }

  private async nameOf(userId: string) {
    const [row] = await this.db.query(`SELECT display_name FROM profiles WHERE user_id = $1`, [userId]);
    return row?.display_name?.trim() || 'Viro user';
  }

  /** Ends and expiry share this: tell the audience, then erase the room. The
   * Moment row itself stays (history keeps its ENDED/EXPIRED state) but keeps
   * no participants, messages, reactions, knocks or invitations, so the room
   * cannot be rejoined, inspected or resurrected. */
  private async closeRoom(row: any, type: string) {
    await this.notify(row, type, { momentId: row.id });
    await this.db.query(`DELETE FROM moment_participants WHERE moment_id = $1`, [row.id]);
    await this.db.query(`DELETE FROM moment_messages WHERE moment_id = $1`, [row.id]);
    await this.db.query(`DELETE FROM moment_knocks WHERE moment_id = $1`, [row.id]);
    await this.db.query(`DELETE FROM moment_invitations WHERE moment_id = $1`, [row.id]);
  }

  private async notify(row: any, type: string, extra: Record<string, unknown> = {}) {
    const peers = await this.db.query(`SELECT DISTINCT u.id FROM users u WHERE u.id = $1 OR (
      NOT EXISTS (SELECT 1 FROM blocks b WHERE (b.blocker_user_id = u.id AND b.blocked_user_id = $1)
        OR (b.blocker_user_id = $1 AND b.blocked_user_id = u.id)) AND
      (($2 = 'CONNECTIONS' AND EXISTS (SELECT 1 FROM viro_connections c WHERE c.status = 'ACCEPTED'
        AND ((c.requester_user_id = $1 AND c.recipient_user_id = u.id) OR
             (c.recipient_user_id = $1 AND c.requester_user_id = u.id)))) OR
       ($2 = 'CONTACTS' AND EXISTS (SELECT 1 FROM contact_matches c WHERE c.user_id = $1
          AND c.matched_user_id = u.id AND c.expires_at > now()))))`, [row.creator_user_id, row.visibility]);
    // Invalidate rather than shipping activity in queued frames. HTTP rechecks
    // permission after a block/revocation, including events already in flight.
    await Promise.allSettled(peers.map((p: any) => this.realtime.deliverToUser(p.id, { type, payload: { ...extra } })));
  }

  private dto(r: any) {
    return { id: r.id, creatorUserId: r.creator_user_id, type: r.type, text: r.text,
      visibility: r.visibility, displayName: r.display_name || 'Viro user', avatarUrl: publicAvatarUrl(r.visible_avatar),
      createdAt: new Date(r.created_at).toISOString(), expiresAt: new Date(r.expires_at).toISOString(),
      allowVoice: r.type === 'FREE', participantCount: r.participant_count ?? 0 };
  }
}
