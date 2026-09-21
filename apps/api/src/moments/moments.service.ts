import { BadRequestException, ConflictException, ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { RealtimeRegistry } from '../realtime/realtime.registry';
import { PushService } from '../push/push.service';
import { publicAvatarUrl } from '../users/avatar.util';

export const MOMENT_TYPES = ['FREE', 'BREAK', 'LISTENING', 'WATCHING', 'GAMING', 'WORKING', 'CUSTOM'];
export const MOMENT_AUDIENCES = ['CONNECTIONS', 'CONTACTS'];
export const MOMENT_REACTIONS = ['❤️', '😂', '🔥', '👏', '👍'];
export interface CreateMoment { type: string; text?: string; visibility: string; durationMinutes: number }
/** One sealed copy of a room message, addressed to one device. */
export interface MomentEnvelope { deviceId: string; ciphertext: string; type?: number }
/** A room message as the sender offers it: readable text, or sealed copies. */
export interface MomentMessageInput { body?: string | null; envelopes?: MomentEnvelope[] }

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
      (SELECT count(*)::int FROM moment_participants mp WHERE mp.moment_id = m.id) AS participant_count,
      -- Reactions to the Moment itself, already ranked: the emoji most people
      -- chose first, so the client shows a leading reaction without counting.
      -- A tie goes to the reaction that was there first, not to whichever emoji
      -- happens to sort lower — that order changes with the database collation.
      (SELECT json_agg(t) FROM (SELECT mc.emoji, count(*)::int AS count FROM moment_cheers mc
        WHERE mc.moment_id = m.id GROUP BY mc.emoji
        ORDER BY count(*) DESC, MIN(mc.created_at), mc.emoji) t) AS cheers,
      (SELECT mc.emoji FROM moment_cheers mc WHERE mc.moment_id = m.id AND mc.user_id = $1) AS my_cheer
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

  /**
   * Changes who can see a Moment that is already running.
   *
   * Nothing is migrated or recalculated: every read derives the audience from
   * this column, so narrowing removes the Moment from the feeds of people who
   * no longer qualify at their next read, and ejects them from the room the
   * next time they touch it (liveMoment rechecks). Widening announces it to
   * the new audience, which is how they learn it exists at all.
   *
   * What it cannot do is unsay anything: someone who was in the room read what
   * was said while they were there. The room tells everyone the audience
   * changed rather than letting it happen quietly behind them.
   */
  async setVisibility(userId: string, id: string, visibility: string) {
    const before = await this.liveMoment(userId, id);
    if (before.creator_user_id !== userId) throw new ForbiddenException('Only the host can change who can see this.');
    if (before.visibility === visibility) return this.get(userId, id);
    const [rows] = await this.db.query(`UPDATE moments SET visibility = $3, visibility_changed_at = now()
      WHERE id = $2 AND creator_user_id = $1 AND status = 'ACTIVE' AND expires_at > now() RETURNING *`,
    [userId, id, visibility]);
    const row = rows[0];
    if (!row) throw new NotFoundException('Moment unavailable.');
    // Both audiences are told: the one that can no longer see it, so the card
    // goes away, and the one that now can, so it appears.
    await this.notify(before, 'moment.updated', { momentId: id });
    await this.notify(row, 'moment.updated', { momentId: id });
    await this.announceRoom(id, 'moment.audience', { momentId: id, visibility });
    return this.get(userId, id);
  }

  /**
   * A reaction to the Moment itself, which anyone who can see it may leave —
   * joining the room is a bigger step than saying "nice".
   *
   * One per person: reacting again switches the emoji, and null clears it.
   */
  async cheer(userId: string, id: string, emoji: string | null) {
    await this.liveMoment(userId, id);
    if (emoji !== null && !MOMENT_REACTIONS.includes(emoji)) throw new BadRequestException('Unsupported reaction.');
    if (emoji === null) {
      await this.db.query(`DELETE FROM moment_cheers WHERE moment_id = $1 AND user_id = $2`, [id, userId]);
    } else {
      await this.db.query(`INSERT INTO moment_cheers (moment_id, user_id, emoji) VALUES ($1,$2,$3)
        ON CONFLICT (moment_id, user_id) DO UPDATE SET emoji = EXCLUDED.emoji, created_at = now()`, [id, userId, emoji]);
    }
    await this.announceRoom(id, 'moment.cheer', { momentId: id, userId, emoji });
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

  async join(userId: string, deviceId: string, id: string) {
    const moment = await this.liveMoment(userId, id);
    // Idempotent: reconnecting or re-tapping Join cannot duplicate a row or a
    // second "joined" announcement to people already in the room.
    const inserted = this.rowsOf(await this.db.query(`INSERT INTO moment_participants (moment_id, user_id)
      VALUES ($1,$2) ON CONFLICT DO NOTHING RETURNING user_id`, [id, userId]));
    if (inserted.some((r) => r.user_id === userId)) {
      await this.announceRoom(id, 'moment.joined', { momentId: id, userId, displayName: await this.nameOf(userId) });
    }
    return this.room(userId, deviceId, id, moment);
  }

  async leave(userId: string, id: string) {
    const hosts = this.rowsOf(await this.db.query(`SELECT 1 AS one FROM moments WHERE id = $2 AND creator_user_id = $1`, [userId, id]));
    if (hosts.length > 0) throw new BadRequestException('End your Moment instead of leaving it.');
    const removed = this.rowsOf(await this.db.query(`DELETE FROM moment_participants WHERE moment_id = $1 AND user_id = $2 RETURNING user_id`, [id, userId]));
    if (removed.length === 0) throw new NotFoundException('You are not in this room.');
    await this.announceRoom(id, 'moment.left', { momentId: id, userId });
    return { success: true };
  }

  async room(userId: string, deviceId: string, id: string, preloaded?: any) {
    const moment = preloaded ?? await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) {
      // Guests see rooms by being in them; the host is a participant from creation.
      throw new ForbiddenException('Join this Moment to see its room.');
    }
    const participants = await this.db.query(`SELECT mp.user_id, mp.joined_at, p.display_name,
      (mp.user_id = $2) AS is_host FROM moment_participants mp
      LEFT JOIN profiles p ON p.user_id = mp.user_id WHERE mp.moment_id = $1 ORDER BY mp.joined_at`, [id, moment.creator_user_id]);
    // The copy addressed to the device asking, and no other: this join is the
    // only reason the backlog of a sealed room is readable at all, and it can
    // only ever hand over one device's own envelope.
    const messages = await this.db.query(`SELECT mm.*, p.display_name,
      e.ciphertext, e.envelope_type FROM moment_messages mm
      LEFT JOIN profiles p ON p.user_id = mm.sender_user_id
      LEFT JOIN moment_message_envelopes e ON e.message_id = mm.id AND e.device_id = $2::uuid
      WHERE mm.moment_id = $1 ORDER BY mm.created_at DESC LIMIT 100`, [id, deviceId || null]);
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
      // A sealed message with no copy for this device was said before this
      // device was in the room. There is no way to show it and no honest
      // placeholder for it either, so it is simply not part of this device's
      // view of the room.
      messages: messages.reverse()
        .filter((m: any) => m.body !== null || m.ciphertext)
        .map((m: any) => ({
          id: m.id, momentId: m.moment_id, senderUserId: m.sender_user_id,
          senderDeviceId: m.sender_device_id, senderName: m.display_name || 'Viro user',
          body: m.body, sealed: m.body === null,
          envelope: m.ciphertext ? { ciphertext: m.ciphertext, type: m.envelope_type ?? 1 } : null,
          createdAt: new Date(m.created_at).toISOString(),
          reactions: byMessage.get(m.id) ?? [],
        })),
    };
  }

  /**
   * Says something in the room, sealed when the sender could seal it.
   *
   * A sealed message arrives as one copy per device and no body, and that is
   * how it is stored: this server keeps ciphertext it cannot open, addressed
   * to devices it cannot be. A plaintext body is still accepted, because a
   * room where one person's phone has no keys yet would otherwise be a room
   * where that person cannot speak — the client decides, and the room tells
   * everyone which of the two it got.
   *
   * Envelopes are only accepted for devices actually in the room. Otherwise
   * the sender could address a copy to a device that left, and the server
   * would hold it and hand it back.
   */
  async message(userId: string, deviceId: string, id: string, input: MomentMessageInput) {
    await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment to chat.');
    const offered = (input.envelopes ?? []).filter((e) => e?.deviceId && e?.ciphertext);
    const text = (input.body ?? '').trim().slice(0, 500);
    if (!text && offered.length === 0) throw new BadRequestException('Message is empty.');

    // Only devices in the room may be addressed: otherwise a sender could
    // leave a copy here for a device that has gone, and this server would
    // hold it and hand it over. The sender's own other devices count — they
    // are in the room, through their owner.
    const addressable = offered.length === 0 ? [] : await this.db.query(
      `SELECT d.id FROM devices d JOIN moment_participants mp
       ON mp.user_id = d.user_id AND mp.moment_id = $1 WHERE d.id = ANY($2::uuid[])`,
      [id, offered.map((e) => e.deviceId)]);
    const inRoom = new Set<string>(addressable.map((r: any) => r.id));
    const envelopes = offered.filter((e) => inRoom.has(e.deviceId));
    if (offered.length > 0 && envelopes.length === 0) {
      throw new BadRequestException('No one in this room could be addressed.');
    }
    const sealed = envelopes.length > 0;

    const [row] = await this.db.query(`INSERT INTO moment_messages (moment_id, sender_user_id, sender_device_id, body)
      VALUES ($1,$2,$3,$4) RETURNING *`, [id, userId, deviceId || null, sealed ? null : text]);
    if (sealed) {
      const tuples = envelopes
        .map((_, i) => `($1::uuid, $${i * 3 + 2}::uuid, $${i * 3 + 3}::text, $${i * 3 + 4}::smallint)`)
        .join(',');
      await this.db.query(
        `INSERT INTO moment_message_envelopes (message_id, device_id, user_id, ciphertext, envelope_type)
         SELECT v.message_id, v.device_id, d.user_id, v.ciphertext, v.envelope_type
         FROM (VALUES ${tuples}) AS v(message_id, device_id, ciphertext, envelope_type)
         JOIN devices d ON d.id = v.device_id
         ON CONFLICT DO NOTHING`,
        [row.id, ...envelopes.flatMap((e) => [e.deviceId, e.ciphertext, e.type ?? 1])],
      );
    }

    const base = {
      id: row.id, momentId: id, senderUserId: userId, senderDeviceId: deviceId || null,
      senderName: await this.nameOf(userId),
      createdAt: new Date(row.created_at).toISOString(), reactions: [] as unknown[],
    };
    if (sealed) {
      // One frame per device, each carrying only that device's own copy — no
      // readable body, and nobody else's ciphertext.
      await Promise.allSettled(envelopes.map((e) => this.realtime.deliverToDevice(e.deviceId, {
        type: 'moment.message',
        payload: { momentId: id, message: { ...base, body: null, sealed: true,
          envelope: { ciphertext: e.ciphertext, type: e.type ?? 1 } } },
      })));
      return { ...base, body: null, sealed: true };
    }
    await this.announceRoom(id, 'moment.message', { momentId: id, message: { ...base, body: text, sealed: false } });
    return { ...base, body: text, sealed: false };
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
    await this.db.query(`DELETE FROM moment_cheers WHERE moment_id = $1`, [row.id]);
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
      allowVoice: r.type === 'FREE', participantCount: r.participant_count ?? 0,
      // Ranked highest first by the query; myReaction is what this viewer
      // chose, so the button can show as already pressed.
      reactions: (r.cheers ?? []) as { emoji: string; count: number }[],
      reactionCount: ((r.cheers ?? []) as { count: number }[]).reduce((n, c) => n + c.count, 0),
      myReaction: r.my_cheer ?? null,
      visibilityChangedAt: r.visibility_changed_at ? new Date(r.visibility_changed_at).toISOString() : null };
  }
}
