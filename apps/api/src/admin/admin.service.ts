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
