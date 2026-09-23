import { BadRequestException, ConflictException, ForbiddenException, HttpException, HttpStatus, Injectable, Logger, NotFoundException, ServiceUnavailableException } from '@nestjs/common';
import { roomMessages } from './room-messages';
import { moodInvitation } from './mood-words';
import { DataSource } from 'typeorm';
import { RealtimeRegistry } from '../realtime/realtime.registry';
import { PushService } from '../push/push.service';
import { publicAvatarUrl } from '../users/avatar.util';
import { RedisService } from '../redis/redis.service';
import { LiveKitService } from '../livekit/livekit.service';
import { createHmac, timingSafeEqual } from 'crypto';
import { decryptText, encryptText } from '../common/crypto/field-cipher';
import * as fs from 'fs';
import {
  applyPlayback, idlePlayback, MomentPlayback, PlaybackChange, PlaybackError, PlayableMedia,
} from './playback';
import {
  kindOf, looksLike, MAX_AUDIO_BYTES, MAX_VIDEO_BYTES, MediaRange, UploadedMediaProvider,
} from './moment-media.provider';
import {
  applyChoice, applyTimer, ChoiceChange, MomentChoice, MomentTimer, noChoice, noTimer, TimerChange, ToolError,
  TouchKind, withoutPicksOf,
} from './room-tools';

import {
  applyChange, initialState, intentForLegacyType, intentWords, MomentIntent, MomentRuntimeState, RoomChange, RoomChangeError,
} from './room-engine';

/** An upload as multer leaves it on disk. */
export interface IncomingMedia { path: string; size: number; mimetype: string; originalname?: string }
/** How many things one Moment can hold at once. */
const MAX_MEDIA_PER_MOMENT = 20;

export const MOMENT_TYPES = ['FREE', 'BREAK', 'LISTENING', 'WATCHING', 'GAMING', 'WORKING', 'CUSTOM'];
export const MOMENT_AUDIENCES = ['CONNECTIONS', 'CONTACTS'];
/** How the host is, in the four an artwork can draw and a person can answer. */
export const MOMENT_MOODS = ['HAPPY', 'SAD', 'ANGRY', 'CRAZY'];
export const MOMENT_REACTIONS = ['❤️', '😂', '🔥', '👏', '👍'];
export interface CreateMoment { type: string; text?: string; visibility: string; durationMinutes: number; intent?: MomentIntent; invitationText?: string; mood?: string }
/** One sealed copy of a room message, addressed to one device. */
export interface MomentEnvelope { deviceId: string; ciphertext: string; type?: number }
/** A room message as the sender offers it: readable text, or sealed copies. */
export interface MomentMessageInput { body?: string | null; envelopes?: MomentEnvelope[]; replyToId?: string | null }

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
    private readonly redis: RedisService,
    private readonly livekit: LiveKitService,
    private readonly uploads: UploadedMediaProvider,
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
      (SELECT mc.emoji FROM moment_cheers mc WHERE mc.moment_id = m.id AND mc.user_id = $1) AS my_cheer,
      -- Who is already inside, by name and face, so a card can show people
      -- rather than a number. Capped at three: this is "who is here", not a
      -- guest list, and a room with twenty people in it still only needs to
      -- show that it is busy.
      --
      -- Each face is subject to that person's own photo setting, the same rule
      -- the host's avatar above follows. Somebody who has restricted their
      -- photo does not lose that because they walked into a room.
      (SELECT json_agg(h) FROM (
        SELECT pp.display_name,
          CASE WHEN pp.photo_visibility = 'EVERYONE' OR
            (pp.photo_visibility = 'CONTACTS' AND (EXISTS (SELECT 1 FROM contact_matches c
                WHERE c.user_id = $1 AND c.matched_user_id = mp2.user_id AND c.expires_at > now())
              OR EXISTS (SELECT 1 FROM viro_connections c WHERE c.status = 'ACCEPTED' AND
                ((c.requester_user_id = $1 AND c.recipient_user_id = mp2.user_id) OR
                 (c.recipient_user_id = $1 AND c.requester_user_id = mp2.user_id)))))
          THEN pp.avatar_url ELSE NULL END AS avatar_url
        FROM moment_participants mp2
        LEFT JOIN profiles pp ON pp.user_id = mp2.user_id
        WHERE mp2.moment_id = m.id AND mp2.user_id <> $1
        ORDER BY mp2.joined_at LIMIT 3
      ) h) AS here
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
      [row] = await this.db.query(`INSERT INTO moments (creator_user_id,type,text,visibility,intent,invitation_text,mood,expires_at)
        VALUES ($1,$2,$3,$4,$6,$7,$8,now() + $5 * interval '1 minute') RETURNING *`,
      [userId, body.type, body.text?.trim() || null, body.visibility, body.durationMinutes, body.intent ?? null,
        body.invitationText?.trim() || null, body.mood ?? null]);
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

  /**
   * Finish clearing up after closes that did not get that far.
   *
   * Marking a Moment terminal and erasing its room cannot be one transaction:
   * most of the erasing is Redis, LiveKit and files. A failure half way
   * through used to strand the leftovers for good, because [sweep] only ever
   * looked at ACTIVE rows and the Moment was already ENDED.
   *
   * Deliberately not part of [sweep], which runs on every room read: this does
   * real work and belongs on the timer, not in front of somebody waiting for a
   * room to open.
   */
  async tidy() {
    const unfinished = await this.db.query(`SELECT * FROM moments
      WHERE cleaned_at IS NULL AND status <> 'ACTIVE' ORDER BY expires_at LIMIT 20`);
    for (const row of unfinished) {
      // Quietly: everybody was told the first time, and saying a Moment has
      // ended for a second time is worse than the mess being tidied.
      try {
        await this.closeRoom(row, null);
      } catch {
        // Left for the next pass rather than stopping the others.
      }
    }
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
    // A block is absolute: two people who have blocked each other never share
    // a room, whoever's Moment it is. The answer is the same one a Moment the
    // person cannot see gets, so it says nothing about who blocked whom.
    if (!(await this.isParticipant(id, userId)) && (await this.blockedInRoom(id, userId))) {
      throw new NotFoundException('Moment unavailable.');
    }
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
    // Already out — left on another phone, or separated by a block — is still
    // out: leaving twice is not an error, and says nothing about why.
    if (removed.length === 0) return { success: true };
    await this.announceRoom(id, 'moment.left', { momentId: id, userId });
    void this.livekit.removeFromRoom(this.livekit.roomNameForMoment(id), userId);
    await this.dropMediaOf(id, userId);
    await this.dropPicksOf(id, userId);
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
      // What the room is right now. A phone opening or reconnecting takes this
      // as the truth, so it lands in the film if the room became one while it
      // was away — never in the cooking it left.
      state: await this.runtime(moment),
      // Where shared playback is, and the server's clock in milliseconds, so a
      // phone can place itself in the film without asking again.
      playback: await this.playback(id),
      serverNow: Date.now(),
      media: await this.mediaRows(id),
      timer: await this.timer(id),
      choice: await this.choice(id),
      participants: participants.map((p: any) => ({
        userId: p.user_id, displayName: p.display_name || 'Viro user', isHost: p.is_host,
        joinedAt: new Date(p.joined_at).toISOString(),
      })),
      // A sealed message with no copy for this device was said before this
      // device was in the room. There is no way to show it and no honest
      // placeholder for it either, so it is simply not part of this device's
      // view of the room.
      // Every message in the room, including the ones this device cannot open;
      // see room-messages.ts for why that matters and what used to happen.
      messages: roomMessages(messages, byMessage),
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

    // What this answers, if anything. Checked against this room rather than
    // trusted: a reply may only ever point at a message in the room it is
    // being said in, or it becomes a way to ask the server about another one.
    let replyTo: string | null = null;
    if (input.replyToId) {
      const [found] = await this.db.query(
        `SELECT 1 FROM moment_messages WHERE id = $1 AND moment_id = $2`,
        [input.replyToId, id],
      );
      if (!found) throw new BadRequestException('That message is not in this Moment.');
      replyTo = input.replyToId;
    }
    const [row] = await this.db.query(`INSERT INTO moment_messages (moment_id, sender_user_id, sender_device_id, body, reply_to_id)
      VALUES ($1,$2,$3,$4,$5) RETURNING *`, [id, userId, deviceId || null, sealed ? null : text, replyTo]);
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
      createdAt: new Date(row.created_at).toISOString(),
      replyToId: row.reply_to_id ?? null,
      reactions: [] as unknown[],
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
    // An invitation must not promise entry to somebody excluded by the
    // Moment's audience. Keep the same authorization as inbox reads and joins.
    await this.liveMoment(inviteeId, id);
    await this.db.query(`INSERT INTO moment_invitations (moment_id, invitee_user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, [id, inviteeId]);
    const name = await this.nameOf(userId);
    const activity = moment.text?.trim() || moment.type;
    // How they are and what they are asking for, in one sentence. It used to
    // say only that somebody had invited you to a Moment, which is the one
    // thing the person receiving it could already guess — and it left out the
    // part that decides whether you go: how they actually are.
    const told = moodInvitation(
      name,
      moment.mood,
      moment.intent ?? intentForLegacyType(moment.type),
      moment.invitation_text,
    );
    const delivered = await this.realtime.deliverToUser(inviteeId, {
      type: 'moment.invited',
      payload: { momentId: id, inviterName: name, activity, mood: moment.mood ?? null, told: told.title },
    });
    if (!delivered) {
      await this.push.sendToUser(inviteeId, {
        title: told.title,
        body: told.body,
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
    // People waiting at a door of this person's own, counted across every
    // Moment they are hosting. A knock is answered from wherever they happen
    // to be in the app, so the count has to be findable from anywhere too —
    // and it belongs with invitations because both answer the same question:
    // is anybody waiting for me.
    const [knocking] = await this.db.query(`SELECT COUNT(*)::int AS waiting
      FROM moment_knocks k JOIN moments m ON m.id = k.moment_id
      WHERE m.creator_user_id = $1 AND m.status = 'ACTIVE' AND m.expires_at > now()
        AND k.status = 'PENDING'`, [userId]);
    return {
      invitations: rows.map((r: any) => ({ invitationId: r.invitation_id,
        invitedAt: new Date(r.invited_at).toISOString(), moment: this.dto(r) })),
      knocks: knocking?.waiting ?? 0,
    };
  }

  async declineInvitation(userId: string, invitationId: string) {
    const removed = this.rowsOf(await this.db.query(`DELETE FROM moment_invitations WHERE id = $1 AND invitee_user_id = $2 RETURNING id`, [invitationId, userId]));
    if (removed.length === 0) throw new NotFoundException('Invitation unavailable.');
    return { success: true };
  }

  // ------------------------------------------------------------------ shared

  /** Sends a frame to everyone currently in the room (host included). */
  // ----------------------------------------------------------- room engine

  /** Room shape lives in Redis: it changes constantly and matters only while the Moment does. */
  private runtimeKey(momentId: string) {
    return `moment:room:${momentId}`;
  }

  /** Changes to one room are applied one at a time, so two people cannot lose each other's change. */
  private readonly roomLocks = new Map<string, Promise<unknown>>();

  private async serialized<T>(momentId: string, work: () => Promise<T>): Promise<T> {
    const previous = this.roomLocks.get(momentId) ?? Promise.resolve();
    const next = previous.catch(() => undefined).then(work);
    this.roomLocks.set(momentId, next);
    try {
      return await next;
    } finally {
      if (this.roomLocks.get(momentId) === next) this.roomLocks.delete(momentId);
    }
  }

  /**
   * What the room is right now.
   *
   * If Redis has lost it — a restart, an eviction — it is rebuilt from the
   * Moment's intent rather than the room failing to open: the people, the
   * Moment and everything said are in the database, and only the shape of the
   * room is recreated.
   */
  async runtime(moment: any): Promise<MomentRuntimeState> {
    const stored = await this.redis.getJson<MomentRuntimeState>(this.runtimeKey(moment.id));
    if (stored) return stored;
    const intent: MomentIntent = moment.intent ?? intentForLegacyType(moment.type);
    const fresh = initialState(moment.id, intent, moment.creator_user_id);
    await this.saveRuntime(moment, fresh);
    return fresh;
  }

  private async saveRuntime(moment: any, state: MomentRuntimeState) {
    // Kept a little past the Moment's own end, so a late read never rebuilds a
    // room that has just closed; closing deletes it outright anyway.
    const ttl = Math.max(60, Math.ceil((new Date(moment.expires_at).getTime() - Date.now()) / 1000) + 600);
    await this.redis.setJson(this.runtimeKey(moment.id), state, ttl);
  }

  /**
   * Changes what the room is — cooking to a film to quiet — without anyone
   * leaving it.
   *
   * Anyone in the room may change it; the room says who did. Everyone in it is
   * sent the whole new shape rather than a diff, with a revision number, so a
   * phone that missed a frame or got two out of order simply keeps the newest.
   */
  async changeRoom(userId: string, id: string, change: RoomChange) {
    const moment = await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment to change it.');
    return this.serialized(id, async () => {
      const current = await this.runtime(moment);
      let next: MomentRuntimeState;
      try {
        next = applyChange(current, change, userId);
      } catch (e) {
        if (e instanceof RoomChangeError) throw new BadRequestException(e.message);
        throw e;
      }
      await this.saveRuntime(moment, next);
      await this.announceRoom(id, 'moment.state', { momentId: id, state: next });
      return next;
    });
  }

  // --------------------------------------------------------------- presence

  private readonly log = new Logger(MomentsService.name);

  private async blockedInRoom(momentId: string, userId: string) {
    const [row] = await this.db.query(`SELECT 1 FROM moment_participants mp JOIN blocks b ON
      (b.blocker_user_id = $2 AND b.blocked_user_id = mp.user_id) OR (b.blocker_user_id = mp.user_id AND b.blocked_user_id = $2)
      WHERE mp.moment_id = $1 AND mp.user_id <> $2 LIMIT 1`, [momentId, userId]);
    return !!row;
  }

  /**
   * Admission to the room's live media: faces and voices, for the people in
   * this Moment only.
   *
   * The token lets a phone publish its camera and microphone; it does not
   * turn either on. That happens on the phone, only when its person chooses.
   */
  async presence(userId: string, id: string) {
    await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
    if (await this.blockedInRoom(id, userId)) throw new NotFoundException('Moment unavailable.');
    if (!this.livekit.isConfigured()) throw new ServiceUnavailableException("Live video isn't available right now.");
    const creds = await this.livekit.generateMomentToken(id, userId, await this.nameOf(userId));
    return { url: creds.url, token: creds.token, roomName: creds.roomName };
  }

  /**
   * Someone has just blocked someone. In every live Moment they are both in,
   * they stop sharing it: the host keeps their own room; otherwise the person
   * blocked leaves. They are taken out of the media room too, not just the
   * list, since a live connection outlasts the token that opened it.
   */
  async separate(blockerId: string, blockedId: string) {
    const rooms = await this.db.query(`SELECT m.id, m.creator_user_id FROM moments m
      JOIN moment_participants a ON a.moment_id = m.id AND a.user_id = $1
      JOIN moment_participants b ON b.moment_id = m.id AND b.user_id = $2
      WHERE m.status = 'ACTIVE' AND m.expires_at > now()`, [blockerId, blockedId]);
    for (const room of rooms) {
      const leaving = room.creator_user_id === blockedId ? blockerId : blockedId;
      try {
        await this.db.query(`DELETE FROM moment_participants WHERE moment_id = $1 AND user_id = $2`, [room.id, leaving]);
        void this.livekit.removeFromRoom(this.livekit.roomNameForMoment(room.id), leaving);
        await this.announceRoom(room.id, 'moment.left', { momentId: room.id, userId: leaving });
        await this.dropMediaOf(room.id, leaving);
        await this.dropPicksOf(room.id, leaving);
        // The person leaving is no longer in the room to hear it, so they are told directly.
        await this.realtime.deliverToUser(leaving, { type: 'moment.left', payload: { momentId: room.id, userId: leaving } });
      } catch (e) {
        this.log.warn(`Moment separation failed for ${room.id}: ${(e as Error).message}`);
      }
    }
  }

  // ------------------------------------------------------------ shared media

  private playbackKey(momentId: string) {
    return `moment:play:${momentId}`;
  }

  /** Where the room's shared player is. Held in Redis beside the room itself. */
  async playback(momentId: string): Promise<MomentPlayback> {
    return (await this.redis.getJson<MomentPlayback>(this.playbackKey(momentId))) ?? idlePlayback(momentId, Date.now());
  }

  private async savePlayback(moment: any, p: MomentPlayback) {
    const ttl = Math.max(60, Math.ceil((new Date(moment.expires_at).getTime() - Date.now()) / 1000) + 600);
    await this.redis.setJson(this.playbackKey(moment.id), p, ttl);
  }

  private async mediaRows(momentId: string) {
    const rows = await this.db.query(`SELECT mm.id, mm.owner_user_id, mm.kind, mm.title, mm.duration_ms, mm.size_bytes,
      mm.created_at, p.display_name FROM moment_media mm LEFT JOIN profiles p ON p.user_id = mm.owner_user_id
      WHERE mm.moment_id = $1 ORDER BY mm.created_at`, [momentId]);
    return rows.map((r: any) => ({
      id: r.id, kind: r.kind, title: r.title, durationMs: r.duration_ms ?? null, sizeBytes: Number(r.size_bytes),
      ownerUserId: r.owner_user_id, ownerName: r.display_name || 'Viro user', createdAt: new Date(r.created_at).toISOString(),
    }));
  }

  private removeFile(item: any) {
    try { this.uploads.remove(item); } catch (e) { this.log.warn(`Moment media file not removed: ${(e as Error).message}`); }
  }

  /**
   * Something a participant brings from their own phone to watch or listen to
   * together. Checked to be the kind of file it claims, encrypted on disk, and
   * playable only by the people in this Moment while it lasts.
   */
  async shareMedia(userId: string, id: string, file: IncomingMedia | undefined, meta: { title?: string; durationMs?: number }) {
    const discard = () => { if (file?.path) fs.rmSync(file.path, { force: true }); };
    try {
      if (!file?.path || !file.size) throw new BadRequestException('Choose a video or a song to share.');
      const moment = await this.liveMoment(userId, id);
      if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
      const kind = kindOf(file.mimetype);
      if (!kind) throw new BadRequestException('Only videos and music can be shared in a Moment.');
      if (file.size > (kind === 'VIDEO' ? MAX_VIDEO_BYTES : MAX_AUDIO_BYTES)) {
        throw new BadRequestException(kind === 'VIDEO' ? 'That video is too large to share (100 MB at most).' : 'That song is too large to share (30 MB at most).');
      }
      const head = Buffer.alloc(16);
      const fd = fs.openSync(file.path, 'r');
      try { fs.readSync(fd, head, 0, 16, 0); } finally { fs.closeSync(fd); }
      if (!looksLike(file.mimetype, head)) throw new BadRequestException("That file isn't a video or song Viro can play.");
      const [{ count }] = await this.db.query(`SELECT count(*)::int AS count FROM moment_media WHERE moment_id = $1`, [id]);
      if (count >= MAX_MEDIA_PER_MOMENT) throw new BadRequestException('This Moment already has as much as it can hold. Remove something first.');

      const fromName = (file.originalname ?? '').replace(/\.[a-z0-9]{1,5}$/i, '');
      const title = (meta.title?.trim() || fromName.trim() || (kind === 'VIDEO' ? 'A video' : 'A song')).slice(0, 120);
      const durationMs = meta.durationMs != null && Number.isFinite(meta.durationMs) && meta.durationMs > 0
        ? Math.min(Math.round(meta.durationMs), 6 * 60 * 60 * 1000) : null;
      const kept = await this.uploads.keep(file.path);
      const [row] = await this.db.query(`INSERT INTO moment_media
        (moment_id, owner_user_id, provider, kind, mime, title, size_bytes, duration_ms, file_name, file_key)
        VALUES ($1,$2,'UPLOAD',$3,$4,$5,$6,$7,$8,$9) RETURNING id`,
        [moment.id, userId, kind, file.mimetype, title, file.size, durationMs, kept.fileName, kept.fileKey]);
      await this.announceRoom(id, 'moment.media', { momentId: id });
      return (await this.mediaRows(id)).find((m: any) => m.id === row.id);
    } finally {
      discard();
    }
  }

  async listMedia(userId: string, id: string) {
    await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
    return { media: await this.mediaRows(id) };
  }

  /** Takes something back out of the Moment. Only whoever shared it can. */
  async unshareMedia(userId: string, id: string, mediaId: string) {
    await this.liveMoment(userId, id);
    const rows = this.rowsOf(await this.db.query(`DELETE FROM moment_media WHERE id = $1 AND moment_id = $2 AND owner_user_id = $3
      RETURNING *`, [mediaId, id, userId]));
    if (rows.length === 0) throw new NotFoundException('Nothing like that is shared here.');
    await this.afterMediaRemoved(id, rows);
    return { success: true };
  }

  /** Someone left: what they brought goes with them, and stops if it was playing. */
  private async dropMediaOf(momentId: string, userId: string) {
    const rows = this.rowsOf(await this.db.query(`DELETE FROM moment_media WHERE moment_id = $1 AND owner_user_id = $2
      RETURNING *`, [momentId, userId]));
    if (rows.length > 0) await this.afterMediaRemoved(momentId, rows);
  }

  private async afterMediaRemoved(momentId: string, rows: any[]) {
    for (const item of rows) this.removeFile(item);
    const [moment] = await this.db.query(`SELECT * FROM moments WHERE id = $1`, [momentId]);
    if (moment) {
      await this.serialized(`play:${momentId}`, async () => {
        const current = await this.playback(momentId);
        if (current.mediaId && rows.some((r) => r.id === current.mediaId)) {
          const stopped = applyPlayback(current, { op: 'STOP' }, 'viro', Date.now());
          await this.savePlayback(moment, stopped);
          await this.announceRoom(momentId, 'moment.playback', { momentId, playback: stopped, serverNow: Date.now() });
        }
      });
    }
    await this.announceRoom(momentId, 'moment.media', { momentId });
  }

  /**
   * Files nothing refers to any more — their row went with a deleted
   * account, or an upload died half way — are removed. Only files older than
   * an hour, so an upload in progress is never touched.
   */
  async sweepOrphanMedia(olderThanMs = 60 * 60 * 1000) {
    const dir = this.uploads.dir();
    const cutoff = Date.now() - olderThanMs;
    const known = new Set((await this.db.query(`SELECT file_name FROM moment_media`)).map((r: any) => r.file_name));
    let removed = 0;
    const consider = (full: string, orphan: boolean) => {
      try {
        if (orphan && fs.statSync(full).mtimeMs < cutoff) { fs.rmSync(full, { force: true }); removed++; }
      } catch { /* gone already */ }
    };
    for (const name of fs.readdirSync(dir)) {
      if (name.endsWith('.bin')) consider(`${dir}/${name}`, !known.has(name));
    }
    const incoming = this.uploads.incomingDir();
    for (const name of fs.readdirSync(incoming)) consider(`${incoming}/${name}`, true);
    return { removed };
  }

  private streamSecret(): string {
    return process.env.MOMENT_MEDIA_SECRET || process.env.JWT_ACCESS_SECRET || 'dev_access_secret';
  }

  private sign(mediaId: string, userId: string, expires: number): string {
    return createHmac('sha256', this.streamSecret()).update(`moment-media.${mediaId}.${userId}.${expires}`).digest('base64url');
  }

  /**
   * An address a phone's player can fetch without a login header: signed,
   * for this person and this item, and short-lived. It is only a way in —
   * every request still checks that they are in the Moment right now.
   */
  async streamUrl(userId: string, id: string, mediaId: string) {
    const moment = await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
    const [item] = await this.db.query(`SELECT id FROM moment_media WHERE id = $1 AND moment_id = $2`, [mediaId, id]);
    if (!item) throw new NotFoundException('That is no longer here to play.');
    const expires = Math.min(Date.now() + 3 * 60 * 60 * 1000, new Date(moment.expires_at).getTime() + 10 * 60 * 1000);
    const q = new URLSearchParams({ u: userId, e: String(expires), s: this.sign(mediaId, userId, expires) });
    return { url: `/api/v1/moment-media/${mediaId}?${q.toString()}`, expiresAt: new Date(expires).toISOString() };
  }

  /** Serves a byte range to someone holding a valid address and still in the room. */
  async openStream(mediaId: string, userId: string, expires: string, signature: string, range?: string):
    Promise<{ range: MediaRange; partial: boolean } | 'gone' | 'unsatisfiable'> {
    const exp = Number(expires);
    const expected = Buffer.from(this.sign(mediaId, userId, exp));
    const given = Buffer.from(String(signature ?? ''));
    if (!Number.isFinite(exp) || exp < Date.now() || given.length !== expected.length || !timingSafeEqual(given, expected)) return 'gone';
    const [item] = await this.db.query(`SELECT mm.* FROM moment_media mm
      JOIN moments m ON m.id = mm.moment_id AND m.status = 'ACTIVE' AND m.expires_at > now()
      JOIN moment_participants mp ON mp.moment_id = mm.moment_id AND mp.user_id = $2
      WHERE mm.id = $1`, [mediaId, userId]);
    if (!item) return 'gone';
    const size = Number(item.size_bytes);
    if (!range) return { range: this.uploads.open(item), partial: false };
    const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (!m || (m[1] === '' && m[2] === '')) return 'unsatisfiable';
    let start: number; let end: number;
    if (m[1] === '') { start = Math.max(0, size - Number(m[2])); end = size - 1; }
    else { start = Number(m[1]); end = m[2] === '' ? size - 1 : Math.min(Number(m[2]), size - 1); }
    if (start >= size || start > end) return 'unsatisfiable';
    return { range: this.uploads.open(item, start, end), partial: true };
  }

  /**
   * Play, pause, seek, load, stop — for everyone in the room at once.
   * Anyone here may press them; the room says who did.
   */
  async changePlayback(userId: string, id: string, change: PlaybackChange) {
    const moment = await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
    return this.serialized(`play:${id}`, async () => {
      let media: PlayableMedia | null = null;
      if (change.op === 'LOAD') {
        const [row] = await this.db.query(`SELECT id, kind, title, duration_ms FROM moment_media WHERE id = $1 AND moment_id = $2`,
          [change.mediaId ?? null, id]);
        media = row ? { id: row.id, kind: row.kind, title: row.title, durationMs: row.duration_ms ?? null } : null;
      }
      const now = Date.now();
      let next: MomentPlayback;
      try {
        next = applyPlayback(await this.playback(id), change, userId, now, media);
      } catch (e) {
        if (e instanceof PlaybackError) throw new BadRequestException(e.message);
        throw e;
      }
      await this.savePlayback(moment, next);
      await this.announceRoom(id, 'moment.playback', { momentId: id, playback: next, serverNow: now });
      return { playback: next, serverNow: now };
    });
  }

  // ------------------------------------------------------ doing things together

  private timerKey(momentId: string) { return `moment:timer:${momentId}`; }
  private choiceKey(momentId: string) { return `moment:choice:${momentId}`; }

  async timer(momentId: string): Promise<MomentTimer> {
    return (await this.redis.getJson<MomentTimer>(this.timerKey(momentId))) ?? noTimer(momentId);
  }

  async choice(momentId: string): Promise<MomentChoice> {
    return (await this.redis.getJson<MomentChoice>(this.choiceKey(momentId))) ?? noChoice(momentId);
  }

  private async saveTool(moment: any, key: string, value: unknown) {
    const ttl = Math.max(60, Math.ceil((new Date(moment.expires_at).getTime() - Date.now()) / 1000) + 600);
    await this.redis.setJson(key, value, ttl);
  }

  /** The room's kitchen timer: anyone can start, pause, add to or cancel it. */
  async changeTimer(userId: string, id: string, change: TimerChange) {
    const moment = await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
    return this.serialized(`timer:${id}`, async () => {
      const now = Date.now();
      let next: MomentTimer;
      try { next = applyTimer(await this.timer(id), change, userId, now); }
      catch (e) { if (e instanceof ToolError) throw new BadRequestException(e.message); throw e; }
      await this.saveTool(moment, this.timerKey(id), next);
      await this.announceRoom(id, 'moment.timer', { momentId: id, timer: next, serverNow: now });
      return { timer: next, serverNow: now };
    });
  }

  /** A question for the room: ask, answer, decide, clear. */
  async changeChoice(userId: string, id: string, change: ChoiceChange) {
    const moment = await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
    return this.serialized(`choice:${id}`, async () => {
      let next: MomentChoice;
      try { next = applyChoice(await this.choice(id), change, userId); }
      catch (e) { if (e instanceof ToolError) throw new BadRequestException(e.message); throw e; }
      await this.saveTool(moment, this.choiceKey(id), next);
      await this.announceRoom(id, 'moment.choice', { momentId: id, choice: next });
      return { choice: next };
    });
  }

  private async dropPicksOf(momentId: string, userId: string) {
    const [moment] = await this.db.query(`SELECT * FROM moments WHERE id = $1`, [momentId]);
    if (!moment) return;
    await this.serialized(`choice:${momentId}`, async () => {
      const next = withoutPicksOf(await this.choice(momentId), userId);
      if (!next) return;
      await this.saveTool(moment, this.choiceKey(momentId), next);
      await this.announceRoom(momentId, 'moment.choice', { momentId, choice: next });
    });
  }

  /**
   * A heart, a hug, a wave, a tap — felt on the other phone, said with a name.
   * Never stored. To everyone else here, or to one person here. A few a
   * second is plenty; beyond that it would be a buzzer, not a touch.
   */
  async touch(userId: string, id: string, kind: TouchKind, to?: string) {
    await this.liveMoment(userId, id);
    if (!(await this.isParticipant(id, userId))) throw new ForbiddenException('Join this Moment first.');
    if (to && (to === userId || !(await this.isParticipant(id, to)))) throw new BadRequestException("They aren't here.");
    const client = this.redis.getClient();
    const key = `moment:touch:${userId}`;
    const count = await client.incr(key);
    if (count === 1) await client.expire(key, 10);
    if (count > 10) throw new HttpException('Slow down a little.', HttpStatus.TOO_MANY_REQUESTS);
    const payload = { momentId: id, from: userId, fromName: await this.nameOf(userId), kind, to: to ?? null, at: Date.now() };
    const rows = to ? [{ user_id: to }]
      : await this.db.query(`SELECT user_id FROM moment_participants WHERE moment_id = $1 AND user_id <> $2`, [id, userId]);
    await Promise.allSettled(rows.map((r: any) => this.realtime.deliverToUser(r.user_id, { type: 'moment.touch', payload })));
    return { success: true };
  }

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
  /**
   * Erase the room behind a Moment that is over.
   *
   * [type] is the event everyone in it is told; null means this is a retry of
   * a close that did not finish, where they have already been told once.
   *
   * Every step is safe to run again — the deletes are deletes, the Redis keys
   * are gone or were already gone, and keepsake offers conflict away — because
   * this is reached a second time whenever the first attempt failed part way.
   * Only when all of it is through is the Moment written down as cleared up;
   * until then the sweep keeps coming back for it.
   */
  private async closeRoom(row: any, type: string | null) {
    // Before anything is erased, and only from what the room already had.
    await this.offerKeepsakes(row);
    if (type) await this.notify(row, type, { momentId: row.id });
    await this.db.query(`DELETE FROM moment_participants WHERE moment_id = $1`, [row.id]);
    await this.db.query(`DELETE FROM moment_cheers WHERE moment_id = $1`, [row.id]);
    await this.db.query(`DELETE FROM moment_messages WHERE moment_id = $1`, [row.id]);
    await this.db.query(`DELETE FROM moment_knocks WHERE moment_id = $1`, [row.id]);
    await this.db.query(`DELETE FROM moment_invitations WHERE moment_id = $1`, [row.id]);
    // The shape of the room goes with it, so nothing can rebuild a closed room.
    await this.redis.del(this.runtimeKey(row.id));
    await this.redis.del(this.playbackKey(row.id));
    await this.redis.del(this.timerKey(row.id));
    await this.redis.del(this.choiceKey(row.id));
    void this.livekit.closeRoom(this.livekit.roomNameForMoment(row.id));
    // Everything shared into the Moment ends with it, files included.
    const shared = await this.db.query(`SELECT * FROM moment_media WHERE moment_id = $1`, [row.id]);
    for (const item of shared) this.removeFile(item);
    await this.db.query(`DELETE FROM moment_media WHERE moment_id = $1`, [row.id]);
    // Last, and only if everything above got through. Anything that threw
    // leaves this unset, which is the sweep's instruction to try again.
    await this.db.query(`UPDATE moments SET cleaned_at = now() WHERE id = $1`, [row.id]);
  }

  // -------------------------------------------------------------- keepsakes

  /** How long an unanswered ending waits before it leaves nothing behind. */
  private static readonly KEEPSAKE_OFFER_HOURS = 48;

  /**
   * Gathers what this Moment could leave behind, just before its room is
   * erased.
   *
   * Nothing here is kept — these are offers, and an offer nobody accepts is
   * swept away. Everything is metadata: "we watched this" rather than the
   * file, which is deleted at closing exactly as it was before. That is the
   * difference between a Moment that leaves a memory and a Moment that
   * quietly becomes a recording.
   *
   * A Moment somebody spent alone offers nothing: there is no "together" in
   * it to keep.
   */
  private async offerKeepsakes(row: any) {
    const people = await this.db.query(
      `SELECT user_id FROM moment_participants WHERE moment_id = $1`, [row.id]);
    if (people.length < 2) return;

    const endedAt = new Date();
    await this.db.query(`UPDATE moments SET ended_at = $2 WHERE id = $1 AND ended_at IS NULL`,
      [row.id, endedAt]);

    // The right to keep outlives the participant rows, which are about to go.
    const audience = people.map((_: any, i: number) => `($1, $${i + 2}::uuid)`).join(',');
    await this.db.query(
      `INSERT INTO moment_keepsake_audience (moment_id, user_id) VALUES ${audience}
       ON CONFLICT DO NOTHING`,
      [row.id, ...people.map((x: any) => x.user_id)]);

    const offers: { kind: string; title: string; detail: string | null }[] = [];

    // The evening itself: what it was, with the people and the date carried by
    // the Moment. This is the one nearly every Moment has, and often the only
    // one anybody wants.
    offers.push({
      kind: 'MOMENT',
      title: (row.text ?? '').trim() || intentWords(row.intent ?? intentForLegacyType(row.type)),
      detail: null,
    });

    // What was decided, if anything was. A decision made together is exactly
    // the thing that otherwise disappears into a chat nobody scrolls back to.
    const choice = await this.choice(row.id);
    if (choice.status === 'DECIDED' && choice.question) {
      const chosen = choice.options.find((o) => o.id === choice.decided);
      if (chosen) offers.push({ kind: 'DECISION', title: choice.question, detail: chosen.text });
    }

    // What was brought to watch or listen to, by name. The files themselves are
    // deleted seconds from now, and are not what is being kept.
    const shared = await this.db.query(
      `SELECT title, kind FROM moment_media WHERE moment_id = $1 ORDER BY created_at`, [row.id]);
    for (const item of shared) {
      offers.push({
        kind: 'MEDIA',
        title: item.title,
        detail: item.kind === 'AUDIO' ? 'Listened to together' : 'Watched together',
      });
    }

    const expiresAt = new Date(endedAt.getTime() + MomentsService.KEEPSAKE_OFFER_HOURS * 3600_000);
    const tuples = offers
      .map((_, i) => `($1::uuid, $${i * 3 + 2}, $${i * 3 + 3}, $${i * 3 + 4}, $${offers.length * 3 + 2}::timestamptz)`)
      .join(',');
    await this.db.query(
      `INSERT INTO moment_keepsake_offers (moment_id, kind, title, detail, expires_at) VALUES ${tuples}`,
      [row.id,
        ...offers.flatMap((o) => [o.kind, encryptText(o.title), o.detail === null ? null : encryptText(o.detail)]),
        expiresAt]);
  }

  /**
   * The ending, for somebody who was there: how long, with whom, and what
   * could be kept.
   *
   * Readable only by the people who were in the room, and only while the
   * offers live. After that the Moment leaves nothing behind, which is the
   * behaviour somebody who simply closed the app should get.
   */
  async keepsakeOffers(userId: string, momentId: string) {
    const [allowed] = await this.db.query(
      `SELECT 1 FROM moment_keepsake_audience WHERE moment_id = $1 AND user_id = $2`,
      [momentId, userId]);
    if (!allowed) throw new NotFoundException('Moment unavailable.');

    const [moment] = await this.db.query(
      `SELECT id, created_at, ended_at FROM moments WHERE id = $1`, [momentId]);
    if (!moment) throw new NotFoundException('Moment unavailable.');

    // Names, never ids: a Viro id is not a person's name anywhere a person can
    // see it.
    const others = await this.db.query(
      `SELECT p.display_name FROM moment_keepsake_audience a
       LEFT JOIN profiles p ON p.user_id = a.user_id
       WHERE a.moment_id = $1 AND a.user_id <> $2`, [momentId, userId]);

    const offers = await this.db.query(
      `SELECT id, kind, title, detail FROM moment_keepsake_offers
       WHERE moment_id = $1 AND expires_at > now() ORDER BY
         CASE kind WHEN 'MOMENT' THEN 0 WHEN 'DECISION' THEN 1 ELSE 2 END, created_at`,
      [momentId]);
    const mine = await this.db.query(
      `SELECT offer_id FROM moment_keepsakes WHERE moment_id = $1 AND user_id = $2`,
      [momentId, userId]);
    const kept = new Set(mine.map((k: any) => k.offer_id));

    const ended = moment.ended_at ? new Date(moment.ended_at) : new Date();
    return {
      momentId,
      // The pieces, not the sentence: the app writes the ending in its own
      // words, and knows the person's language.
      withPeople: others.map((o: any) => (o.display_name || '').trim() || 'Viro user'),
      togetherMs: Math.max(0, ended.getTime() - new Date(moment.created_at).getTime()),
      endedAt: ended.toISOString(),
      offers: offers.map((o: any) => ({
        id: o.id,
        kind: o.kind,
        title: decryptText(o.title),
        detail: o.detail === null ? null : decryptText(o.detail),
        kept: kept.has(o.id),
      })),
    };
  }

  /**
   * Keeps what somebody chose, and nothing else.
   *
   * An empty list is a real answer and the default one: it means "keep
   * nothing". Keeping is per person — two people who keep the same evening
   * each hold their own, and neither can reach into the other's.
   */
  async keepKeepsakes(userId: string, momentId: string, offerIds: string[]) {
    const [allowed] = await this.db.query(
      `SELECT 1 FROM moment_keepsake_audience WHERE moment_id = $1 AND user_id = $2`,
      [momentId, userId]);
    if (!allowed) throw new NotFoundException('Moment unavailable.');
    const wanted = [...new Set(offerIds)];
    if (wanted.length === 0) return { kept: 0 };

    const offers = await this.db.query(
      `SELECT id, kind, title, detail FROM moment_keepsake_offers
       WHERE moment_id = $1 AND expires_at > now() AND id = ANY($2::uuid[])`,
      [momentId, wanted]);
    if (offers.length === 0) throw new NotFoundException('There is nothing left to keep from this Moment.');

    const tuples = offers
      .map((_: any, i: number) =>
        `($1::uuid, $2::uuid, $${i * 4 + 3}::uuid, $${i * 4 + 4}, $${i * 4 + 5}, $${i * 4 + 6})`)
      .join(',');
    await this.db.query(
      `INSERT INTO moment_keepsakes (moment_id, user_id, offer_id, kind, title, detail)
       VALUES ${tuples} ON CONFLICT (user_id, offer_id) DO NOTHING`,
      [momentId, userId, ...offers.flatMap((o: any) => [o.id, o.kind, o.title, o.detail])]);
    return { kept: offers.length };
  }

  /**
   * What this person has kept: the part of Viro that does not end when a
   * Moment does.
   */
  async keepsakes(userId: string, limit = 100) {
    const rows = await this.db.query(
      `SELECT k.id, k.moment_id, k.kind, k.title, k.detail, k.kept_at, m.created_at
       FROM moment_keepsakes k JOIN moments m ON m.id = k.moment_id
       WHERE k.user_id = $1 ORDER BY k.kept_at DESC LIMIT $2`, [userId, Math.min(limit, 200)]);
    const ids = [...new Set(rows.map((r: any) => r.moment_id))] as string[];
    const people = ids.length === 0 ? [] : await this.db.query(
      `SELECT a.moment_id, p.display_name FROM moment_keepsake_audience a
       LEFT JOIN profiles p ON p.user_id = a.user_id
       WHERE a.moment_id = ANY($1::uuid[]) AND a.user_id <> $2`, [ids, userId]);
    const withWhom = new Map<string, string[]>();
    for (const row of people) {
      const list = withWhom.get(row.moment_id) ?? [];
      list.push((row.display_name || '').trim() || 'Viro user');
      withWhom.set(row.moment_id, list);
    }
    return {
      keepsakes: rows.map((r: any) => ({
        id: r.id,
        momentId: r.moment_id,
        kind: r.kind,
        title: decryptText(r.title),
        detail: r.detail === null ? null : decryptText(r.detail),
        withPeople: withWhom.get(r.moment_id) ?? [],
        happenedAt: new Date(r.created_at).toISOString(),
        keptAt: new Date(r.kept_at).toISOString(),
      })),
    };
  }

  /** Someone's own keepsake, theirs to drop. */
  async forgetKeepsake(userId: string, keepsakeId: string) {
    const removed = this.rowsOf(await this.db.query(
      `DELETE FROM moment_keepsakes WHERE id = $1 AND user_id = $2 RETURNING id`,
      [keepsakeId, userId]));
    if (removed.length === 0) throw new NotFoundException('Keepsake unavailable.');
    return { success: true };
  }

  /**
   * Offers nobody answered. A Moment that ended while everyone had already
   * closed the app leaves nothing behind, which is the right default.
   */
  async sweepKeepsakeOffers() {
    await this.db.query(`DELETE FROM moment_keepsake_offers WHERE expires_at <= now()`);
    // An audience with no offers left and nothing kept is only the right to
    // answer an ending that no longer exists.
    await this.db.query(
      `DELETE FROM moment_keepsake_audience a WHERE
         NOT EXISTS (SELECT 1 FROM moment_keepsake_offers o WHERE o.moment_id = a.moment_id)
         AND NOT EXISTS (SELECT 1 FROM moment_keepsakes k WHERE k.moment_id = a.moment_id)`);
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
      // Why people came together; older Moments derive one from their type.
      intent: r.intent ?? intentForLegacyType(r.type),
      // What they said when they opened it, if they said anything. Never
      // invented: the app says something plain when this is null.
      invitationText: r.invitation_text ?? null,
      // How they are. Null is an answer too, and is not filled in for them.
      mood: r.mood ?? null,
      // Names, never ids, and only the handful the card can show.
      here: ((r.here ?? []) as { display_name: string | null; avatar_url: string | null }[])
        .map((h) => ({
          displayName: (h.display_name || '').trim() || 'Viro user',
          avatarUrl: publicAvatarUrl(h.avatar_url),
        })),
      // Ranked highest first by the query; myReaction is what this viewer
      // chose, so the button can show as already pressed.
      reactions: (r.cheers ?? []) as { emoji: string; count: number }[],
      reactionCount: ((r.cheers ?? []) as { count: number }[]).reduce((n, c) => n + c.count, 0),
      myReaction: r.my_cheer ?? null,
      visibilityChangedAt: r.visibility_changed_at ? new Date(r.visibility_changed_at).toISOString() : null };
  }
}
