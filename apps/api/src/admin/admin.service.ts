import { Injectable, HttpStatus } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { DataSource, ILike, In, Repository } from 'typeorm';
import { User } from '../database/entities/user.entity';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Device } from '../database/entities/device.entity';
import { SecurityEvent } from '../database/entities/security-event.entity';
import { ViroException } from '../common/exceptions/viro.exception';
import { PushService } from '../push/push.service';

@Injectable()
export class AdminService {
  constructor(
    @InjectRepository(User) private readonly users: Repository<User>,
    @InjectRepository(Profile) private readonly profiles: Repository<Profile>,
    @InjectRepository(PhoneIdentity) private readonly phones: Repository<PhoneIdentity>,
    @InjectRepository(Device) private readonly devices: Repository<Device>,
    @InjectRepository(SecurityEvent) private readonly events: Repository<SecurityEvent>,
    private readonly db: DataSource,
    private readonly push: PushService,
  ) {}

  /**
   * What is happening on the platform right now, in the few numbers somebody
   * running it actually opens the page to see.
   *
   * Counted live rather than kept in a table: at this size the queries are
   * cheap, and a counter that drifts from the truth is worse than no counter.
   */
  async overview() {
    const [row] = await this.db.query(`SELECT
      (SELECT count(*)::int FROM users) AS users,
      (SELECT count(*)::int FROM users WHERE status = 'SUSPENDED') AS suspended,
      (SELECT count(*)::int FROM users WHERE created_at > now() - interval '7 days') AS new_this_week,
      (SELECT count(*)::int FROM devices) AS devices,
      (SELECT count(*)::int FROM moments WHERE status = 'ACTIVE' AND expires_at > now()) AS live_moments,
      (SELECT count(*)::int FROM moments WHERE created_at > now() - interval '7 days') AS moments_this_week,
      (SELECT count(*)::int FROM messages WHERE created_at > now() - interval '24 hours') AS messages_today,
      (SELECT count(*)::int FROM security_events WHERE created_at > now() - interval '24 hours') AS events_today,
      (SELECT count(*)::int FROM device_identity_keys) AS keyed_devices`);
    return row ?? {};
  }

  /**
   * Every Moment that is open right now.
   *
   * Deliberately no message bodies and no room contents: running the platform
   * is a reason to see that a room exists and who opened it, not a licence to
   * read what people are saying in it. Ending one is the only control here,
   * and it is the same ending the host would get.
   */
  async liveMoments(limit = 100) {
    return this.db.query(`SELECT m.id, m.creator_user_id, p.display_name, m.intent, m.type, m.mood,
        m.visibility, m.created_at, m.expires_at,
        (SELECT count(*)::int FROM moment_participants mp WHERE mp.moment_id = m.id) AS participants
      FROM moments m LEFT JOIN profiles p ON p.user_id = m.creator_user_id
      WHERE m.status = 'ACTIVE' AND m.expires_at > now()
      ORDER BY m.created_at DESC LIMIT $1`, [Math.min(Math.max(limit, 1), 500)]);
  }

  /**
   * Ends a Moment on the platform's behalf.
   *
   * Marked ENDED and left to the ordinary sweeper, which is what erases the
   * room, tells the people in it and gathers whatever they might keep. Doing
   * it any other way would leave a Moment nobody can enter and nobody was told
   * about.
   */
  async endMoment(id: string) {
    const [rows] = await this.db.query(
      `UPDATE moments SET status = 'ENDED' WHERE id = $1 AND status = 'ACTIVE' RETURNING id`, [id]);
    if (!rows || rows.length === 0) {
      throw new ViroException('NOT_FOUND', 'No live Moment with that id.', HttpStatus.NOT_FOUND);
    }
    return { success: true };
  }

  /** Who is on what plan, newest first. */
  async subscriptions(limit = 100) {
    return this.db.query(`SELECT s.user_id, p.display_name, pl.name AS plan_name, s.status, s.created_at, s.expires_at
      FROM subscriptions s
      LEFT JOIN profiles p ON p.user_id = s.user_id
      LEFT JOIN plans pl ON pl.id = s.plan_id
      ORDER BY s.created_at DESC LIMIT $1`, [Math.min(Math.max(limit, 1), 500)]);
  }

  /** How many people are on each plan. */
  async planTotals() {
    return this.db.query(`SELECT pl.name AS plan_name, s.status, count(*)::int AS people
      FROM subscriptions s LEFT JOIN plans pl ON pl.id = s.plan_id
      GROUP BY pl.name, s.status ORDER BY pl.name`);
  }

  /**
   * Sends a notification, to one person or to everyone with a device.
   *
   * A broadcast is a blunt instrument, so it is capped and it says who sent
   * it: an announcement that cannot be traced back to a person is how a
   * platform ends up shouting at its own users.
   */
  async notify(input: { userId?: string; title: string; body: string; by: string }) {
    const title = input.title.trim().slice(0, 80);
    const body = input.body.trim().slice(0, 240);
    if (!title || !body) {
      throw new ViroException('VALIDATION_ERROR', 'A notification needs a title and a body.', HttpStatus.BAD_REQUEST);
    }
    const payload = { title, body, data: { type: 'announcement' } };
    if (input.userId) {
      await this.push.sendToUser(input.userId, payload);
      await this.record('ADMIN_NOTIFY_USER', input.by, { userId: input.userId, title });
      return { sentTo: 1 };
    }
    const rows = await this.db.query(
      `SELECT DISTINCT user_id FROM push_tokens LIMIT 5000`);
    const ids = rows.map((r: { user_id: string }) => r.user_id);
    await this.push.sendToUsers(ids, payload);
    await this.record('ADMIN_BROADCAST', input.by, { people: ids.length, title });
    return { sentTo: ids.length };
  }

  // ------------------------------------------------------------- campaigns

  /**
   * Every campaign, with whether it is actually running right now.
   *
   * running is computed from the clock rather than read from a column, so a
   * campaign cannot go on claiming to be live after its period has passed.
   */
  async campaigns() {
    return this.db.query(`SELECT c.*,
        (c.status = 'SCHEDULED'
          AND (c.starts_at IS NULL OR c.starts_at <= now())
          AND (c.ends_at IS NULL OR c.ends_at > now())) AS running,
        (SELECT count(*)::int FROM promotions p WHERE p.campaign_id = c.id) AS promotions
      FROM campaigns c ORDER BY c.created_at DESC`);
  }

  /** One campaign and what it says, in the order somebody arranged. */
  async campaign(id: string) {
    const [campaign] = await this.db.query(`SELECT c.*,
        (c.status = 'SCHEDULED'
          AND (c.starts_at IS NULL OR c.starts_at <= now())
          AND (c.ends_at IS NULL OR c.ends_at > now())) AS running
      FROM campaigns c WHERE c.id = $1`, [id]);
    if (!campaign) {
      throw new ViroException('NOT_FOUND', 'No campaign with that id.', HttpStatus.NOT_FOUND);
    }
    const promotions = await this.db.query(
      `SELECT * FROM promotions WHERE campaign_id = $1 ORDER BY position, created_at`, [id]);
    return { ...campaign, promotions };
  }

  /**
   * A period that ends before it starts is refused by the table, and that
   * refusal should read as "you typed the dates the wrong way round" rather
   * than as the server falling over. Postgres raises 23514 for a failed CHECK.
   */
  private periodError(e: unknown): never {
    if ((e as { code?: string }).code === '23514') {
      throw new ViroException(
        'VALIDATION_ERROR',
        'A campaign has to end after it starts.',
        HttpStatus.BAD_REQUEST,
      );
    }
    throw e;
  }

  async createCampaign(input: { name: string; audience?: string; startsAt?: string; endsAt?: string }) {
    const [row] = await this.db
      .query(
        `INSERT INTO campaigns (name, audience, starts_at, ends_at) VALUES ($1, $2, $3, $4) RETURNING *`,
        [input.name.trim(), input.audience ?? 'EVERYONE', input.startsAt || null, input.endsAt || null],
      )
      .catch((e: unknown) => this.periodError(e));
    return row;
  }

  /**
   * Changes only what was sent. A campaign editor that blanks a period because
   * the form did not include it is how scheduled things quietly become
   * permanent.
   */
  async updateCampaign(id: string, patch: Record<string, unknown>) {
    const columns: Record<string, string> = {
      name: 'name', status: 'status', audience: 'audience',
      startsAt: 'starts_at', endsAt: 'ends_at',
    };
    const sets: string[] = [];
    const values: unknown[] = [id];
    for (const [key, column] of Object.entries(columns)) {
      if (patch[key] === undefined) continue;
      values.push(patch[key] === '' ? null : patch[key]);
      sets.push(`${column} = $${values.length}`);
    }
    if (sets.length === 0) return this.campaign(id);
    const [rows] = await this.db
      .query(`UPDATE campaigns SET ${sets.join(', ')}, updated_at = now() WHERE id = $1 RETURNING id`, values)
      .catch((e: unknown) => this.periodError(e));
    if (!rows || rows.length === 0) {
      throw new ViroException('NOT_FOUND', 'No campaign with that id.', HttpStatus.NOT_FOUND);
    }
    return this.campaign(id);
  }

  async deleteCampaign(id: string) {
    const [rows] = await this.db.query(`DELETE FROM campaigns WHERE id = $1 RETURNING id`, [id]);
    if (!rows || rows.length === 0) {
      throw new ViroException('NOT_FOUND', 'No campaign with that id.', HttpStatus.NOT_FOUND);
    }
    return { success: true };
  }

  /** Adds a promotion at the end of its campaign. */
  async addPromotion(campaignId: string, input: { title: string; body?: string; action?: string }) {
    const [last] = await this.db.query(
      `SELECT coalesce(max(position), -1) + 1 AS next FROM promotions WHERE campaign_id = $1`, [campaignId]);
    const [row] = await this.db.query(
      `INSERT INTO promotions (campaign_id, title, body, action, position)
       VALUES ($1, $2, $3, $4, $5) RETURNING *`,
      [campaignId, input.title.trim(), input.body?.trim() || null, input.action?.trim() || null, last?.next ?? 0],
    ).catch((e: { code?: string }) => {
      if (e.code === '23503') {
        throw new ViroException('NOT_FOUND', 'No campaign with that id.', HttpStatus.NOT_FOUND);
      }
      throw e;
    });
    return row;
  }

  async updatePromotion(id: string, patch: { title?: string; body?: string; action?: string }) {
    const columns: Record<string, string> = { title: 'title', body: 'body', action: 'action' };
    const sets: string[] = [];
    const values: unknown[] = [id];
    for (const [key, column] of Object.entries(columns)) {
      if (patch[key as keyof typeof patch] === undefined) continue;
      const value = patch[key as keyof typeof patch];
      values.push(value === '' ? null : value);
      sets.push(`${column} = $${values.length}`);
    }
    if (sets.length === 0) return { success: true };
    const [rows] = await this.db.query(
      `UPDATE promotions SET ${sets.join(', ')} WHERE id = $1 RETURNING id`, values);
    if (!rows || rows.length === 0) {
      throw new ViroException('NOT_FOUND', 'No promotion with that id.', HttpStatus.NOT_FOUND);
    }
    return { success: true };
  }

  async deletePromotion(id: string) {
    const [rows] = await this.db.query(`DELETE FROM promotions WHERE id = $1 RETURNING id`, [id]);
    if (!rows || rows.length === 0) {
      throw new ViroException('NOT_FOUND', 'No promotion with that id.', HttpStatus.NOT_FOUND);
    }
    return { success: true };
  }

  /**
   * Writes down an order somebody arranged by hand.
   *
   * Every position is rewritten from the list that arrived, in one statement,
   * so a drag that lands cannot leave two promotions claiming the same place.
   * Ids belonging to another campaign are ignored rather than moved, which
   * stops a stale page dragging somebody else's list about.
   */
  async reorderPromotions(campaignId: string, ids: string[]) {
    if (ids.length === 0) return { success: true };
    const values = ids.map((_, i) => `($${i + 2}::uuid, ${i})`).join(',');
    await this.db.query(
      `UPDATE promotions p SET position = v.position
       FROM (VALUES ${values}) AS v(id, position)
       WHERE p.id = v.id AND p.campaign_id = $1`,
      [campaignId, ...ids],
    );
    return { success: true };
  }

  /** Every admin action worth being able to ask "who did that?" about. */
  private async record(kind: string, by: string, detail: Record<string, unknown>) {
    await this.db
      .query(
        `INSERT INTO security_events (user_id, event_type, severity, metadata) VALUES ($1, $2, 'INFO', $3)`,
        [null, kind, JSON.stringify({ by, ...detail })],
      )
      .catch(() => undefined);
  }

  async listUsers(q?: string, limit = 50) {
    const take = Math.min(Math.max(limit, 1), 200);
    if (q && q.trim()) {
      const term = `%${q.trim()}%`;
      const phones = await this.phones.find({ where: { phoneE164: ILike(term) }, take });
      const profiles = await this.profiles.find({
        where: [{ displayName: ILike(term) }, { viroId: ILike(term) }],
        take,
      });
      const ids = Array.from(
        new Set([...phones.map((p) => p.userId), ...profiles.map((p) => p.userId)]),
      ).slice(0, take);
      if (!ids.length) return [];
      const users = await this.users.find({ where: { id: In(ids) } });
      return this.summaries(users);
    }
    const users = await this.users.find({ order: { createdAt: 'DESC' }, take });
    return this.summaries(users);
  }

  async getUser(id: string) {
    const user = await this.users.findOne({ where: { id } });
    if (!user) {
      throw new ViroException('NOT_FOUND', 'User not found.', HttpStatus.NOT_FOUND);
    }
    const [profile, phone, devices] = await Promise.all([
      this.profiles.findOne({ where: { userId: id } }),
      this.phones.findOne({ where: { userId: id } }),
      this.devices.find({ where: { userId: id }, order: { lastSeenAt: 'DESC' } }),
    ]);
    return {
      id: user.id,
      status: user.status,
      adminRole: user.adminRole,
      createdAt: user.createdAt,
      displayName: profile?.displayName ?? '',
      viroId: profile?.viroId ?? null,
      phoneE164: phone?.phoneE164 ?? null,
      devices: devices.map((d) => ({
        id: d.id,
        platform: d.platform,
        lastSeenAt: d.lastSeenAt,
        revokedAt: d.revokedAt,
      })),
    };
  }

  async setStatus(id: string, status: 'ACTIVE' | 'SUSPENDED') {
    const user = await this.users.findOne({ where: { id } });
    if (!user) {
      throw new ViroException('NOT_FOUND', 'User not found.', HttpStatus.NOT_FOUND);
    }
    if (user.status === 'DELETED') {
      throw new ViroException('FORBIDDEN', 'Deleted accounts cannot be restored here.', HttpStatus.FORBIDDEN);
    }
    user.status = status;
    await this.users.save(user);
    if (status === 'SUSPENDED') {
      await this.devices
        .createQueryBuilder()
        .update()
        .set({ revokedAt: () => 'NOW()' })
        .where('user_id = :id AND revoked_at IS NULL', { id })
        .execute();
    }
    return this.getUser(id);
  }

  async securityEvents(limit = 50) {
    return this.events.find({
      order: { createdAt: 'DESC' },
      take: Math.min(Math.max(limit, 1), 200),
    });
  }

  private async summaries(users: User[]) {
    const ids = users.map((u) => u.id);
    const profileRows = ids.length
      ? await this.profiles
          .createQueryBuilder('p')
          .where('p.user_id IN (:...ids)', { ids })
          .getMany()
      : [];
    const byUser = new Map(profileRows.map((p) => [p.userId, p]));
    return users.map((u) => ({
      id: u.id,
      status: u.status,
      adminRole: u.adminRole,
      createdAt: u.createdAt,
      displayName: byUser.get(u.id)?.displayName ?? '',
      viroId: byUser.get(u.id)?.viroId ?? null,
    }));
  }
}
