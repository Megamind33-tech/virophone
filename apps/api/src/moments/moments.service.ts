import { ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { RealtimeRegistry } from '../realtime/realtime.registry';
import { publicAvatarUrl } from '../users/avatar.util';

export const MOMENT_TYPES = ['FREE', 'BREAK', 'LISTENING', 'WATCHING', 'GAMING', 'WORKING', 'CUSTOM'];
export const MOMENT_AUDIENCES = ['CONNECTIONS', 'CONTACTS'];
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
  constructor(private readonly db: DataSource, private readonly realtime: RealtimeRegistry) {}

  async now(userId: string) {
    const rows = await this.db.query(`SELECT m.*, p.display_name,
      CASE WHEN m.creator_user_id = $1 OR p.photo_visibility = 'EVERYONE' OR
        (p.photo_visibility = 'CONTACTS' AND (EXISTS (SELECT 1 FROM contact_matches c
          WHERE c.user_id = $1 AND c.matched_user_id = m.creator_user_id AND c.expires_at > now())
        OR EXISTS (SELECT 1 FROM viro_connections c WHERE c.status = 'ACCEPTED' AND
          ((c.requester_user_id = $1 AND c.recipient_user_id = m.creator_user_id) OR
           (c.recipient_user_id = $1 AND c.requester_user_id = m.creator_user_id)))))
        THEN p.avatar_url ELSE NULL END AS visible_avatar
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
    await this.notify(row, 'moment.ended');
    return { success: true };
  }

  async sweep() {
    // Claim atomically across replicas. Reads reject expired rows even if a
    // worker was stopped; restarting cannot revive an expired Moment.
    const rows = await this.db.query(`UPDATE moments SET status = 'EXPIRED'
      WHERE status = 'ACTIVE' AND expires_at <= now() RETURNING *`);
    for (const row of rows[0] ?? []) await this.notify(row, 'moment.expired');
  }

  private async notify(row: any, type: string) {
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
    await Promise.allSettled(peers.map((p: any) => this.realtime.deliverToUser(p.id, { type, payload: {} })));
  }

  private dto(r: any) {
    return { id: r.id, creatorUserId: r.creator_user_id, type: r.type, text: r.text,
      visibility: r.visibility, displayName: r.display_name || 'Viro user', avatarUrl: publicAvatarUrl(r.visible_avatar),
      createdAt: new Date(r.created_at).toISOString(), expiresAt: new Date(r.expires_at).toISOString(),
      allowVoice: r.type === 'FREE' };
  }
}
