import { Injectable, HttpStatus } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { PushService } from '../push/push.service';
import { BlocksService } from '../blocks/blocks.service';
import { RedisService } from '../redis/redis.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { publicAvatarUrl } from '../users/avatar.util';

export const SIGNAL_KINDS = [
  'THINKING_OF_YOU',
  'MISS_YOU',
  'HERE',
  'NEED_YOUR_VOICE',
  'PROUD',
  'MADE_ME_SMILE',
  'HOLD_ME',
  'CHECKING_ON_YOU',
] as const;
export type SignalKind = (typeof SIGNAL_KINDS)[number];

/** Repeats inside this window become one stronger signal, not five pushes. */
const GROUP_WINDOW = '45 seconds';
/** How long ago a matching signal from the other side still counts as mutual. */
const MUTUAL_WINDOW = '30 minutes';

@Injectable()
export class SignalsService {
  constructor(
    private readonly db: DataSource,
    private readonly push: PushService,
    private readonly blocks: BlocksService,
    private readonly redis: RedisService,
  ) {}

  /** Raw non-SELECT queries come back as [rows, affected]; SELECTs as rows. */
  private rowsOf(result: unknown): any[] {
    const r = result as any;
    return Array.isArray(r) && r.length === 2 && Array.isArray(r[0]) && typeof r[1] === 'number'
      ? r[0]
      : Array.isArray(r) ? r : [];
  }

  /**
   * One person reaches for another. The signal is stored privately, counted,
   * and delivered to the other phone exactly once — no matter how many times
   * they tap in a burst.
   */
  async send(fromId: string, toId: string, kind: string) {
    if (!SIGNAL_KINDS.includes(kind as SignalKind)) {
      throw new ViroException('VALIDATION_ERROR', 'That is not a signal Viro knows.', HttpStatus.BAD_REQUEST);
    }
    if (fromId === toId) {
      throw new ViroException('VALIDATION_ERROR', 'A signal needs someone to reach.', HttpStatus.BAD_REQUEST);
    }
    // Only inside a real connection, and never past a block. A blocked person
    // must not learn anything from this either, so the answer is the same
    // ordinary refusal messaging uses.
    if (await this.blocks.isBlocked(fromId, toId)) {
      throw new ViroException('FORBIDDEN', 'This person is unavailable.', HttpStatus.FORBIDDEN);
    }
    const connected: { ok: boolean }[] = await this.db.query(
      `SELECT 1 AS ok FROM viro_connections
        WHERE status = 'ACCEPTED'
          AND ((requester_user_id = $1 AND recipient_user_id = $2) OR (requester_user_id = $2 AND recipient_user_id = $1))`,
      [fromId, toId],
    );
    if (connected.length === 0) {
      throw new ViroException('FORBIDDEN', 'You are not connected on Viro yet.', HttpStatus.FORBIDDEN);
    }
    // A signal is a touch, not a buzzer: a burst has one voice.
    const client = this.redis.getClient();
    const key = `signal:${fromId}:${toId}`;
    const burst = await client.incr(key);
    if (burst === 1) await client.expire(key, 10);
    if (burst > 10) throw new ViroException('RATE_LIMITED', 'They know.', HttpStatus.TOO_MANY_REQUESTS);

    const [sender] = await this.profileOf(fromId);

    // Repeat taps land in the row they are still talking through.
    const open = this.rowsOf(await this.db.query(
      `UPDATE intimate_signals SET count = count + 1, sent_at = now(), state = 'PUSHED'
        WHERE id = (
          SELECT id FROM intimate_signals
           WHERE sender_user_id = $1 AND recipient_user_id = $2 AND kind = $3
             AND sent_at > now() - interval '${GROUP_WINDOW}'
           ORDER BY sent_at DESC LIMIT 1
        )
        RETURNING id, count`,
      [fromId, toId, kind],
    ));
    let signalId: string;
    let count: number;
    if (open.length > 0) {
      signalId = open[0].id;
      count = Number(open[0].count);
    } else {
      const inserted = this.rowsOf(await this.db.query(
        `INSERT INTO intimate_signals (sender_user_id, recipient_user_id, kind) VALUES ($1, $2, $3) RETURNING id, count`,
        [fromId, toId, kind],
      ));
      signalId = inserted[0].id;
      count = 1;
    }

    // Did the other person reach back recently? Then this is no longer one
    // person thinking alone, and both phones deserve to know it.
    const mutualRow = this.rowsOf(await this.db.query(
      `UPDATE intimate_signals SET mutual = true
        WHERE id = (
          SELECT id FROM intimate_signals
           WHERE sender_user_id = $2 AND recipient_user_id = $1 AND kind = $3
             AND sent_at > now() - interval '${MUTUAL_WINDOW}'
           ORDER BY sent_at DESC LIMIT 1
        )
        RETURNING id`,
      [fromId, toId, kind],
    ));
    const mutual = mutualRow.length > 0;
    if (mutual) {
      await this.db.query(`UPDATE intimate_signals SET mutual = true WHERE id = $1`, [signalId]);
    }

    const today: { total: number }[] = await this.db.query(
      `SELECT COALESCE(SUM(count), 0) AS total FROM intimate_signals
        WHERE sender_user_id = $1 AND recipient_user_id = $2 AND kind = $3
          AND first_sent_at >= date_trunc('day', now())`,
      [fromId, toId, kind],
    );
    const todayCount = Number(today[0].total);

    // One wake-up carries everything the phone needs to present it without
    // asking the server anything first.
    await this.push.sendToUser(toId, {
      highPriority: true,
      data: {
        type: 'signal',
        signalId,
        kind,
        fromUserId: fromId,
        fromName: sender?.name ?? 'Someone',
        fromAvatar: sender?.avatar ?? '',
        count: String(count),
        todayCount: String(todayCount),
        mutual: String(mutual),
      },
    });
    if (mutual) {
      await this.push.sendToUser(fromId, {
        highPriority: true,
        data: {
          type: 'signal.mutual',
          kind,
          withUserId: toId,
        },
      });
    }
    return { ok: true, count, todayCount, mutual };
  }

  /** The phone reporting what happened to a signal it was given. */
  async ack(userId: string, signalId: string, state: 'RECEIVED' | 'PRESENTED' | 'OPENED') {
    await this.db.query(
      `UPDATE intimate_signals SET state = $3
        WHERE id = $1 AND recipient_user_id = $2 AND state <> 'RESPONDED'`,
      [signalId, userId, state],
    );
    return { ok: true };
  }

  /**
   * The one small answer a signal may get: another signal, straight back.
   * It reaches the first person's phone the same way theirs reached this one.
   */
  async respond(userId: string, signalId: string, kind: string) {
    if (!SIGNAL_KINDS.includes(kind as SignalKind)) {
      throw new ViroException('VALIDATION_ERROR', 'That is not a signal Viro knows.', HttpStatus.BAD_REQUEST);
    }
    const answered = this.rowsOf(await this.db.query(
      `UPDATE intimate_signals SET state = 'RESPONDED', responded_kind = $3, responded_at = now()
        WHERE id = $1 AND recipient_user_id = $2 AND state <> 'RESPONDED'
        RETURNING sender_user_id AS sender`,
      [signalId, userId, kind],
    ));
    if (answered.length === 0) return { ok: true };
    const toId = answered[0].sender;
    if (!(await this.blocks.isBlocked(userId, toId))) {
      const [responder] = await this.profileOf(userId);
      await this.push.sendToUser(toId, {
        highPriority: true,
        data: {
          type: 'signal.response',
          kind,
          fromUserId: userId,
          fromName: responder?.name ?? 'Someone',
          fromAvatar: responder?.avatar ?? '',
        },
      });
    }
    return { ok: true };
  }

  private async profileOf(userId: string): Promise<{ name: string; avatar: string }[]> {
    const rows: { name: string; avatar: string | null }[] = await this.db.query(
      `SELECT display_name AS name, avatar_url AS avatar FROM profiles WHERE user_id = $1`,
      [userId],
    );
    return rows.map((r) => ({ name: r.name, avatar: publicAvatarUrl(r.avatar) ?? '' }));
  }
}
