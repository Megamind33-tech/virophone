import { Injectable, HttpStatus } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import {
  Commitment,
  ImportantDate,
  Relationship,
  RelationshipCheckin,
  RelationshipSettings,
  UserAchievement,
} from '../database/entities/relationship.entity';
import { Profile } from '../database/entities/profile.entity';
import { ViroException } from '../common/exceptions/viro.exception';
import { LoopsService, LoopStateDto } from './loops.service';
import {
  WEEKDAY_NAMES,
  addDays,
  dayMonthLabel,
  daysBetween,
  localDayKey,
  localParts,
  minutesOf,
  nextAnnual,
  safeZone,
  weekStart,
  weekdayOf,
  isoWeekKey,
} from './local-time';

export const PERSONAL_TYPES = ['PARTNER', 'FAMILY', 'PARENT', 'CHILD', 'FRIEND', 'BEST_FRIEND', 'RELATIVE', 'CUSTOM'];
export const PROFESSIONAL_TYPES = [
  'CLIENT', 'CUSTOMER', 'EMPLOYEE', 'MANAGER', 'BUSINESS_PARTNER', 'SUPPLIER', 'PROSPECT', 'COLLEAGUE', 'CUSTOM',
];
export const CADENCES = ['DAILY', 'WEEKLY', 'MONTHLY', 'EVERY_N_DAYS', 'WEEKDAY'];
export const DATE_KINDS = [
  'BIRTHDAY', 'ANNIVERSARY', 'FIRST_MEETING', 'WEDDING_ANNIVERSARY', 'GRADUATION', 'CHILD_BIRTHDAY',
  'CONTRACT_RENEWAL', 'PAYMENT', 'FOLLOW_UP', 'BUSINESS_REVIEW', 'CUSTOM',
];
export const VIBES = ['CLOSE', 'FAMILY', 'FRIENDS', 'WORK'];

/**
 * Health is a quiet, private signal derived ONLY from targets and dates the
 * user set. It never grades the relationship; it says whether the user is
 * keeping the promise they made to themselves.
 */
export type HealthCode =
  | 'COMMITMENT_DUE'
  | 'FOLLOW_UP_DUE'
  | 'DUE_TODAY'
  | 'NEEDS_ATTENTION'
  | 'LOOP_WAITING'
  | 'DATE_SOON'
  | 'ON_TRACK'
  | 'NO_TARGET';

const HEALTH_RANK: Record<HealthCode, number> = {
  COMMITMENT_DUE: 0,
  FOLLOW_UP_DUE: 1,
  DUE_TODAY: 2,
  NEEDS_ATTENTION: 3,
  LOOP_WAITING: 4,
  DATE_SOON: 5,
  ON_TRACK: 6,
  NO_TARGET: 7,
};

export interface RelationshipInput {
  subjectUserId?: string | null;
  subjectPhone?: string | null;
  displayName?: string | null;
  category?: string;
  relationshipType?: string;
  customLabel?: string | null;
  vibe?: string | null;
  targetCadence?: string | null;
  targetCount?: number;
  targetEveryDays?: number | null;
  targetWeekday?: number | null;
  targetLabel?: string | null;
  remindersEnabled?: boolean;
  notes?: string | null;
}

interface Ctx {
  now: Date;
  tz: string;
  today: string;
}

@Injectable()
export class RelationshipsService {
  constructor(
    @InjectRepository(Relationship) private readonly relRepo: Repository<Relationship>,
    @InjectRepository(ImportantDate) private readonly dateRepo: Repository<ImportantDate>,
    @InjectRepository(Commitment) private readonly commitRepo: Repository<Commitment>,
    @InjectRepository(RelationshipCheckin) private readonly checkinRepo: Repository<RelationshipCheckin>,
    @InjectRepository(RelationshipSettings) private readonly settingsRepo: Repository<RelationshipSettings>,
    @InjectRepository(UserAchievement) private readonly achRepo: Repository<UserAchievement>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    private readonly loops: LoopsService,
  ) {}

  private fail(code: string, message: string, status: HttpStatus): never {
    throw new ViroException(code as never, message, status);
  }

  // ------------------------------------------------------------- settings

  async settings(userId: string, tz?: string): Promise<RelationshipSettings> {
    let s = await this.settingsRepo.findOne({ where: { userId } });
    if (!s) s = await this.settingsRepo.save(this.settingsRepo.create({ userId, timezone: safeZone(tz) }));
    else if (tz && safeZone(tz) !== s.timezone && safeZone(tz) === tz) {
      s.timezone = tz;
      await this.settingsRepo.save(s);
    }
    return s;
  }

  async updateSettings(userId: string, patch: Partial<RelationshipSettings>) {
    const s = await this.settings(userId);
    const hhmm = (v?: string) => (v && /^\d{2}:\d{2}$/.test(v) ? v : undefined);
    s.quietStart = hhmm(patch.quietStart) ?? s.quietStart;
    s.quietEnd = hhmm(patch.quietEnd) ?? s.quietEnd;
    s.briefTime = hhmm(patch.briefTime) ?? s.briefTime;
    if (patch.frequency && ['LOW', 'NORMAL', 'HIGH'].includes(patch.frequency)) s.frequency = patch.frequency;
    for (const k of [
      'briefEnabled', 'personalReminders', 'professionalReminders', 'dateReminders',
      'loopNotifications', 'achievementNotifications',
    ] as const) {
      if (typeof patch[k] === 'boolean') (s as unknown as Record<string, boolean>)[k] = patch[k] as boolean;
    }
    if (patch.timezone) s.timezone = safeZone(patch.timezone);
    s.updatedAt = new Date();
    return this.settingsRepo.save(s);
  }

  // --------------------------------------------------------- relationships

  private keyFor(input: { subjectUserId?: string | null; subjectPhone?: string | null }): string {
    if (input.subjectPhone && /^\+[1-9]\d{6,14}$/.test(input.subjectPhone)) return `p:${input.subjectPhone}`;
    if (input.subjectUserId) return `u:${input.subjectUserId}`;
    this.fail('VALIDATION_ERROR', 'A relationship needs a phone number or a Viro account.', HttpStatus.BAD_REQUEST);
  }

  private async findExisting(ownerId: string, input: { subjectUserId?: string | null; subjectPhone?: string | null }) {
    const where = [];
    if (input.subjectPhone) where.push({ ownerUserId: ownerId, subjectPhone: input.subjectPhone });
    if (input.subjectUserId) where.push({ ownerUserId: ownerId, subjectUserId: input.subjectUserId });
    return where.length ? this.relRepo.findOne({ where }) : null;
  }

  async upsert(ownerId: string, input: RelationshipInput): Promise<Relationship> {
    let rel = await this.findExisting(ownerId, input);
    if (!rel) {
      rel = this.relRepo.create({ ownerUserId: ownerId, subjectKey: this.keyFor(input) });
    }
    if (input.subjectUserId) rel.subjectUserId = input.subjectUserId;
    if (input.subjectPhone) rel.subjectPhone = input.subjectPhone;
    if (input.displayName !== undefined) rel.displayName = input.displayName?.trim().slice(0, 120) || null;
    if (input.category && ['PERSONAL', 'PROFESSIONAL'].includes(input.category)) rel.category = input.category;
    if (input.relationshipType) {
      const t = input.relationshipType.toUpperCase();
      if (![...PERSONAL_TYPES, ...PROFESSIONAL_TYPES].includes(t)) {
        this.fail('VALIDATION_ERROR', 'Unknown relationship type.', HttpStatus.BAD_REQUEST);
      }
      rel.relationshipType = t;
      if (!input.category) rel.category = PERSONAL_TYPES.includes(t) && t !== 'CUSTOM' ? 'PERSONAL' : rel.category;
      if (!input.category && PROFESSIONAL_TYPES.includes(t) && t !== 'CUSTOM') rel.category = 'PROFESSIONAL';
    }
    if (input.customLabel !== undefined) rel.customLabel = input.customLabel?.trim().slice(0, 40) || null;
    if (input.vibe !== undefined) rel.vibe = input.vibe && VIBES.includes(input.vibe) ? input.vibe : null;
    if (input.targetCadence !== undefined) {
      if (input.targetCadence && !CADENCES.includes(input.targetCadence)) {
        this.fail('VALIDATION_ERROR', 'Unknown target.', HttpStatus.BAD_REQUEST);
      }
      rel.targetCadence = input.targetCadence || null;
    }
    if (input.targetCount !== undefined) rel.targetCount = Math.max(1, Math.min(31, Math.floor(input.targetCount || 1)));
    if (input.targetEveryDays !== undefined) {
      rel.targetEveryDays = input.targetEveryDays ? Math.max(1, Math.min(365, Math.floor(input.targetEveryDays))) : null;
    }
    if (input.targetWeekday !== undefined) {
      rel.targetWeekday = input.targetWeekday === null ? null : Math.max(0, Math.min(6, Math.floor(input.targetWeekday)));
    }
    if (input.targetLabel !== undefined) rel.targetLabel = input.targetLabel?.trim().slice(0, 80) || null;
    if (input.remindersEnabled !== undefined) rel.remindersEnabled = !!input.remindersEnabled;
    if (input.notes !== undefined) rel.notes = input.notes?.slice(0, 4000) || null;
    if (rel.targetCadence === 'EVERY_N_DAYS' && !rel.targetEveryDays) rel.targetEveryDays = 7;
    if (rel.targetCadence === 'WEEKDAY' && rel.targetWeekday === null) rel.targetWeekday = 1;
    rel.updatedAt = new Date();
    return this.relRepo.save(rel);
  }

  async own(ownerId: string, id: string): Promise<Relationship> {
    const rel = await this.relRepo.findOne({ where: { id, ownerUserId: ownerId } });
    if (!rel) this.fail('NOT_FOUND', 'Not found.', HttpStatus.NOT_FOUND);
    return rel;
  }

  async remove(ownerId: string, id: string) {
    await this.relRepo.delete({ id, ownerUserId: ownerId });
    return { ok: true };
  }

  // ------------------------------------------------------ dates / checkins

  async addDate(ownerId: string, relationshipId: string, d: {
    kind: string; label?: string | null; month: number; day: number; year?: number | null; remindDaysBefore?: number;
  }) {
    await this.own(ownerId, relationshipId);
    const kind = (d.kind || '').toUpperCase();
    if (!DATE_KINDS.includes(kind)) this.fail('VALIDATION_ERROR', 'Unknown date type.', HttpStatus.BAD_REQUEST);
    if (!(d.month >= 1 && d.month <= 12 && d.day >= 1 && d.day <= 31)) {
      this.fail('VALIDATION_ERROR', 'Invalid date.', HttpStatus.BAD_REQUEST);
    }
    return this.dateRepo.save(
      this.dateRepo.create({
        ownerUserId: ownerId,
        relationshipId,
        kind,
        label: d.label?.trim().slice(0, 80) || null,
        month: d.month,
        day: d.day,
        year: d.year ?? null,
        remindDaysBefore: Math.max(0, Math.min(30, d.remindDaysBefore ?? 1)),
      }),
    );
  }

  async removeDate(ownerId: string, dateId: string) {
    await this.dateRepo.delete({ id: dateId, ownerUserId: ownerId });
    return { ok: true };
  }

  async checkIn(ownerId: string, relationshipId: string, note?: string) {
    await this.own(ownerId, relationshipId);
    return this.checkinRepo.save(
      this.checkinRepo.create({ ownerUserId: ownerId, relationshipId, note: note?.slice(0, 120) || null }),
    );
  }

  // ----------------------------------------------------------- commitments

  async addCommitment(ownerId: string, c: {
    text: string; dueAt: string; kind?: string; relationshipId?: string | null; subjectUserId?: string | null;
    subjectPhone?: string | null; displayName?: string | null; conversationId?: string | null; messageId?: string | null;
  }) {
    const text = (c.text || '').trim().slice(0, 280);
    const due = new Date(c.dueAt);
    if (!text || Number.isNaN(due.getTime())) this.fail('VALIDATION_ERROR', 'A commitment needs what and when.', HttpStatus.BAD_REQUEST);
    let relationshipId = c.relationshipId ?? null;
    if (relationshipId) await this.own(ownerId, relationshipId);
    else if (c.subjectUserId || c.subjectPhone) {
      // Remembering a promise quietly makes the person someone who matters.
      const rel = (await this.findExisting(ownerId, c)) ??
        (await this.upsert(ownerId, { subjectUserId: c.subjectUserId, subjectPhone: c.subjectPhone, displayName: c.displayName }));
      relationshipId = rel.id;
    }
    const kind = ['CALL', 'SEND', 'MEET', 'FOLLOW_UP', 'OTHER'].includes((c.kind || '').toUpperCase())
      ? c.kind!.toUpperCase() : 'OTHER';
    return this.commitRepo.save(
      this.commitRepo.create({
        ownerUserId: ownerId,
        relationshipId,
        subjectUserId: c.subjectUserId ?? null,
        conversationId: c.conversationId ?? null,
        messageId: c.messageId ?? null,
        kind,
        text,
        dueAt: due,
      }),
    );
  }

  async listCommitments(ownerId: string, status?: string) {
    return this.commitRepo.find({
      where: { ownerUserId: ownerId, ...(status ? { status } : {}) },
      order: { dueAt: 'ASC' },
      take: 200,
    });
  }

  async updateCommitment(ownerId: string, id: string, patch: { status?: string; dueAt?: string; text?: string }) {
    const c = await this.commitRepo.findOne({ where: { id, ownerUserId: ownerId } });
    if (!c) this.fail('NOT_FOUND', 'Not found.', HttpStatus.NOT_FOUND);
    if (patch.status && ['OPEN', 'DONE', 'DISMISSED'].includes(patch.status)) {
      c.status = patch.status;
      c.completedAt = patch.status === 'DONE' ? new Date() : null;
    }
    if (patch.dueAt && !Number.isNaN(new Date(patch.dueAt).getTime())) c.dueAt = new Date(patch.dueAt);
    if (patch.text?.trim()) c.text = patch.text.trim().slice(0, 280);
    return this.commitRepo.save(c);
  }

  // ------------------------------------------------------------ the engine

  /**
   * Moments the owner reached out: their own messages and Loop answers to
   * this person, calls that connected (either direction), and check-ins they
   * logged. Messages the person sent that went unanswered do not count as
   * the owner checking in.
   */
  private async interactions(ownerId: string, rel: Relationship, since: Date): Promise<Date[]> {
    const out: Date[] = [];
    if (rel.subjectUserId) {
      const [a, b] = [ownerId, rel.subjectUserId].sort();
      const msgs: { at: Date }[] = await this.relRepo.query(
        `SELECT m.created_at AS at FROM messages m JOIN conversations c ON c.id = m.conversation_id
         WHERE c.dm_key = $1 AND m.sender_user_id = $2 AND m.type IN ('TEXT','VOICE','IMAGE','LOOP')
           AND m.deliver_at IS NULL AND m.created_at > $3`,
        [`${a}:${b}`, ownerId, since],
      );
      const calls: { at: Date }[] = await this.relRepo.query(
        `SELECT COALESCE(answered_at, started_at) AS at FROM calls
         WHERE answered_at IS NOT NULL AND started_at > $3
           AND ((caller_user_id = $1 AND callee_user_id = $2) OR (caller_user_id = $2 AND callee_user_id = $1))`,
        [ownerId, rel.subjectUserId, since],
      );
      const loopAnswers: { at: Date }[] = await this.relRepo.query(
        `SELECT a.created_at AS at FROM loop_answers a JOIN loops l ON l.id = a.loop_id
         JOIN conversation_participants p ON p.conversation_id = l.conversation_id AND p.user_id = $2
         WHERE a.user_id = $1 AND a.created_at > $3`,
        [ownerId, rel.subjectUserId, since],
      );
      for (const r of [...msgs, ...calls, ...loopAnswers]) out.push(new Date(r.at));
    }
    const manual = await this.checkinRepo.find({ where: { relationshipId: rel.id } });
    for (const m of manual) if (m.at > since) out.push(m.at);
    return out.sort((x, y) => x.getTime() - y.getTime());
  }

  name(rel: Relationship): string {
    return rel.displayName?.trim() || rel.subjectPhone || 'them';
  }

  /** The emoji shown beside a person; warm for personal, a case for work. */
  static icon(rel: Pick<Relationship, 'category' | 'relationshipType'>): string {
    if (rel.category === 'PROFESSIONAL') return '💼';
    switch (rel.relationshipType) {
      case 'PARTNER': return '💙';
      case 'PARENT': case 'FAMILY': case 'RELATIVE': case 'CHILD': return '❤️';
      case 'BEST_FRIEND': return '💛';
      default: return '🤝';
    }
  }

  static defaultVibe(rel: Pick<Relationship, 'category' | 'relationshipType' | 'vibe'>): string {
    if (rel.vibe) return rel.vibe;
    if (rel.category === 'PROFESSIONAL') return 'WORK';
    if (['PARTNER', 'BEST_FRIEND'].includes(rel.relationshipType)) return 'CLOSE';
    if (['FAMILY', 'PARENT', 'CHILD', 'RELATIVE'].includes(rel.relationshipType)) return 'FAMILY';
    return 'FRIENDS';
  }

  /**
   * The sentence Viro uses when a target is not yet met. Worded for the kind
   * of relationship — a mother is checked on, a client is followed up — and
   * never judgemental: it reminds, it does not accuse.
   */
  private phrase(rel: Relationship, situation: 'today' | 'week' | 'month' | 'gap' | 'weekday_due' | 'weekday_missed', n = 0): string {
    const who = this.name(rel);
    const t = rel.relationshipType;
    const work = rel.category === 'PROFESSIONAL';
    if (situation === 'gap') {
      if (work && ['CLIENT', 'CUSTOMER', 'PROSPECT', 'SUPPLIER'].includes(t)) {
        return `You haven't followed up with ${who} in ${n} days.`;
      }
      return `It's been ${n} days since you checked in with ${who}.`;
    }
    if (situation === 'weekday_due') {
      const label = rel.targetLabel || (work ? 'weekly check-in' : 'check-in');
      return `Your ${label} with ${who} is due today.`;
    }
    if (situation === 'weekday_missed') {
      const label = rel.targetLabel || 'check-in';
      return `Your ${label} with ${who} was due ${WEEKDAY_NAMES[rel.targetWeekday ?? 1]}.`;
    }
    if (situation === 'today') {
      if (t === 'PARTNER') return `You and ${who} haven't talked today.`;
      if (t === 'PARENT' || t === 'FAMILY' || t === 'RELATIVE' || t === 'CHILD') return `You haven't spoken to ${who} today. Check on them. ❤️`;
      if (work) return `You haven't been in touch with ${who} today.`;
      return `You haven't talked to ${who} today.`;
    }
    const span = situation === 'week' ? 'this week' : 'this month';
    if (work && ['CLIENT', 'CUSTOMER', 'PROSPECT'].includes(t)) return `You haven't followed up with ${who} ${span}.`;
    if (t === 'PARENT' || t === 'FAMILY' || t === 'RELATIVE' || t === 'CHILD') return `You haven't checked on ${who} ${span}.`;
    return `You haven't caught up with ${who} ${span}.`;
  }

  /** Target status for one relationship from its interaction days. */
  private targetStatus(rel: Relationship, days: Set<string>, lastKey: string | null, ctx: Ctx) {
    const { today } = ctx;
    const cad = rel.targetCadence;
    if (!cad) return { code: 'NO_TARGET' as HealthCode, text: lastKey ? `Last in touch ${this.ago(lastKey, today)}` : 'No target set', period: null, done: 0, needed: 0, met: false };
    const countIn = (from: string, to: string) => [...days].filter((d) => d >= from && d <= to).length;
    if (cad === 'DAILY') {
      const met = days.has(today);
      return met
        ? { code: 'ON_TRACK' as HealthCode, text: 'Checked in today', period: today, done: 1, needed: 1, met }
        : { code: 'DUE_TODAY' as HealthCode, text: this.phrase(rel, 'today'), period: today, done: 0, needed: 1, met };
    }
    if (cad === 'WEEKLY' || cad === 'MONTHLY') {
      const from = cad === 'WEEKLY' ? weekStart(today) : `${today.slice(0, 7)}-01`;
      const periodEnd = cad === 'WEEKLY' ? addDays(from, 6) : this.monthEnd(today);
      const done = countIn(from, today);
      const needed = Math.max(1, rel.targetCount || 1);
      const daysLeft = daysBetween(today, periodEnd) + 1;
      const label = `${Math.min(done, needed)}/${needed} ${cad === 'WEEKLY' ? 'this week' : 'this month'}`;
      if (done >= needed) return { code: 'ON_TRACK' as HealthCode, text: `On track · ${label}`, period: from, done, needed, met: true };
      const remaining = needed - done;
      if (remaining > daysLeft) return { code: 'NEEDS_ATTENTION' as HealthCode, text: this.phrase(rel, cad === 'WEEKLY' ? 'week' : 'month'), period: from, done, needed, met: false };
      if (remaining === daysLeft && !days.has(today)) return { code: 'DUE_TODAY' as HealthCode, text: this.phrase(rel, cad === 'WEEKLY' ? 'week' : 'month'), period: from, done, needed, met: false };
      return { code: 'ON_TRACK' as HealthCode, text: label, period: from, done, needed, met: false };
    }
    if (cad === 'EVERY_N_DAYS') {
      const n = rel.targetEveryDays || 7;
      const gap = lastKey ? daysBetween(lastKey, today) : n;
      if (gap >= n) return { code: 'FOLLOW_UP_DUE' as HealthCode, text: lastKey ? this.phrase(rel, 'gap', gap) : `Follow-up with ${this.name(rel)} is due.`, period: lastKey, done: 0, needed: 1, met: false };
      const left = n - gap;
      return { code: 'ON_TRACK' as HealthCode, text: left === 1 ? 'Follow-up due tomorrow' : `Next follow-up in ${left} days`, period: lastKey, done: 1, needed: 1, met: true };
    }
    // WEEKDAY: a fixed day each week, e.g. "weekly review every Friday".
    const wd = rel.targetWeekday ?? 1;
    const todayWd = weekdayOf(today);
    if (todayWd === wd) {
      return days.has(today)
        ? { code: 'ON_TRACK' as HealthCode, text: 'Done today', period: today, done: 1, needed: 1, met: true }
        : { code: 'DUE_TODAY' as HealthCode, text: this.phrase(rel, 'weekday_due'), period: today, done: 0, needed: 1, met: false };
    }
    const lastOcc = addDays(today, -((todayWd - wd + 7) % 7));
    const doneSince = [...days].some((d) => d >= lastOcc);
    return doneSince
      ? { code: 'ON_TRACK' as HealthCode, text: `Next: ${WEEKDAY_NAMES[wd]}`, period: lastOcc, done: 1, needed: 1, met: true }
      : { code: 'NEEDS_ATTENTION' as HealthCode, text: this.phrase(rel, 'weekday_missed'), period: lastOcc, done: 0, needed: 1, met: false };
  }

  private monthEnd(key: string): string {
    const y = +key.slice(0, 4);
    const m = +key.slice(5, 7);
    const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
    return `${key.slice(0, 7)}-${String(last).padStart(2, '0')}`;
  }

  private ago(dayKey: string, today: string): string {
    const n = daysBetween(dayKey, today);
    if (n <= 0) return 'today';
    if (n === 1) return 'yesterday';
    if (n < 7) return `${n} days ago`;
    return dayMonthLabel(dayKey);
  }

  private dateLabel(d: ImportantDate): string {
    if (d.label) return d.label;
    return ({
      BIRTHDAY: 'Birthday', ANNIVERSARY: 'Anniversary', FIRST_MEETING: 'First meeting',
      WEDDING_ANNIVERSARY: 'Wedding anniversary', GRADUATION: 'Graduation', CHILD_BIRTHDAY: "Child's birthday",
      CONTRACT_RENEWAL: 'Contract renewal', PAYMENT: 'Payment date', FOLLOW_UP: 'Follow-up',
      BUSINESS_REVIEW: 'Business review', CUSTOM: 'Important date',
    } as Record<string, string>)[d.kind] ?? 'Important date';
  }

  /** "Tomorrow is your anniversary ❤️" / "ABC Ltd's contract review is in 3 days." */
  private dateSentence(rel: Relationship, d: ImportantDate, daysAway: number): string {
    const label = this.dateLabel(d);
    const who = this.name(rel);
    const personalOwn = ['ANNIVERSARY', 'WEDDING_ANNIVERSARY', 'FIRST_MEETING'].includes(d.kind) && rel.relationshipType === 'PARTNER';
    const when = daysAway === 0 ? 'today' : daysAway === 1 ? 'tomorrow' : `in ${daysAway} days`;
    if (personalOwn) {
      return daysAway === 0 ? `Today is your ${label.toLowerCase()} ❤️` : daysAway === 1 ? `Tomorrow is your ${label.toLowerCase()} ❤️` : `Your ${label.toLowerCase()} is ${when}.`;
    }
    if (rel.category === 'PROFESSIONAL') return `${who}'s ${label.toLowerCase()} is ${when}.`;
    if (d.kind === 'BIRTHDAY') return `${who}'s birthday is ${when}.${daysAway <= 1 ? ' 🎉' : ''}`;
    return `${who}'s ${label.toLowerCase()} is ${when}.`;
  }

  private commitmentSentence(rel: Relationship | undefined, c: Commitment, ctx: Ctx): string {
    const who = rel ? this.name(rel) : 'them';
    const dueDay = localDayKey(c.dueAt, ctx.tz);
    const p = localParts(c.dueAt, ctx.tz);
    const time = `${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`;
    const when = dueDay === ctx.today ? (p.hour === 0 && p.minute === 0 ? 'today' : `at ${time}`) : dueDay < ctx.today ? 'earlier' : dayMonthLabel(dueDay);
    const text = c.text.replace(/\.$/, '');
    if (c.kind === 'CALL') return `You promised to call ${who} ${when}.`;
    if (dueDay < ctx.today) return `You told ${who} you'd ${text} — still open.`;
    return `You told ${who} you'd ${text} ${when === 'today' ? 'today' : when}.`;
  }

  /**
   * Everything about one person the owner can see: status, next date, open
   * commitments, Loops. Shared by the dashboard, the inbox and the chat.
   */
  private async evaluate(ownerId: string, rel: Relationship, ctx: Ctx, extras: {
    dates: ImportantDate[]; commitments: Commitment[]; loops: LoopStateDto[];
  }) {
    const since = new Date(ctx.now.getTime() - 200 * 86_400_000);
    const inter = await this.interactions(ownerId, rel, since);
    const days = new Set(inter.map((d) => localDayKey(d, ctx.tz)));
    const last = inter.length ? inter[inter.length - 1] : null;
    const lastKey = last ? localDayKey(last, ctx.tz) : null;
    const target = this.targetStatus(rel, days, lastKey, ctx);

    const upcoming = extras.dates
      .map((d) => {
        const next = nextAnnual(ctx.today, d.month, d.day, d.year);
        return next ? { d, next, daysAway: daysBetween(ctx.today, next) } : null;
      })
      .filter((x): x is { d: ImportantDate; next: string; daysAway: number } => !!x)
      .sort((a, b) => a.daysAway - b.daysAway);
    const nextDate = upcoming[0] ?? null;

    const open = extras.commitments.filter((c) => c.status === 'OPEN');
    const dueCommitments = open.filter((c) => localDayKey(c.dueAt, ctx.tz) <= ctx.today);

    // A Loop waits on me once it has opened today (its time has passed, or
    // the other person has already answered) and I haven't answered yet.
    const nowMin = localParts(ctx.now, ctx.tz).hour * 60 + localParts(ctx.now, ctx.tz).minute;
    const waitingLoops = extras.loops.filter(
      (l) => l.active && l.periodKey && !l.myAnswer &&
        (l.answeredBy.length > 0 || l.frequency !== 'DAILY' && l.frequency !== 'WEEKDAYS' && l.frequency !== 'CUSTOM' || nowMin >= minutesOf(l.timeOfDay)),
    );

    const flags: { code: HealthCode; text: string }[] = [];
    for (const c of dueCommitments) flags.push({ code: 'COMMITMENT_DUE', text: this.commitmentSentence(rel, c, ctx) });
    if (!['ON_TRACK', 'NO_TARGET'].includes(target.code)) flags.push({ code: target.code, text: target.text });
    for (const l of waitingLoops) {
      flags.push({
        code: 'LOOP_WAITING',
        text: l.answeredBy.length > 0
          ? `${this.name(rel)} answered ${l.title}. Your turn.`
          : rel.relationshipType === 'PARTNER'
            ? `You and ${this.name(rel)} haven't completed ${l.title} yet.`
            : `${l.title} is waiting for you.`,
      });
    }
    if (nextDate && nextDate.daysAway <= Math.max(3, nextDate.d.remindDaysBefore)) {
      flags.push({ code: 'DATE_SOON', text: this.dateSentence(rel, nextDate.d, nextDate.daysAway) });
    }
    flags.sort((a, b) => HEALTH_RANK[a.code] - HEALTH_RANK[b.code]);
    const health = flags[0] ?? { code: target.code, text: target.text };

    return {
      rel,
      inter,
      days,
      lastKey,
      target,
      health,
      flags,
      nextDate,
      upcoming,
      openCommitments: open,
      dueCommitments,
      waitingLoops,
    };
  }

  private relationshipDto(ev: Awaited<ReturnType<RelationshipsService['evaluate']>>, dates: ImportantDate[], loops: LoopStateDto[], ctx: Ctx) {
    const r = ev.rel;
    return {
      id: r.id,
      subjectUserId: r.subjectUserId,
      subjectPhone: r.subjectPhone,
      displayName: r.displayName,
      category: r.category,
      relationshipType: r.relationshipType,
      customLabel: r.customLabel,
      vibe: RelationshipsService.defaultVibe(r),
      vibeExplicit: r.vibe,
      icon: RelationshipsService.icon(r),
      targetCadence: r.targetCadence,
      targetCount: r.targetCount,
      targetEveryDays: r.targetEveryDays,
      targetWeekday: r.targetWeekday,
      targetLabel: r.targetLabel,
      targetText: this.targetText(r),
      remindersEnabled: r.remindersEnabled,
      notes: r.notes,
      health: { code: ev.health.code, text: ev.health.text },
      flags: ev.flags,
      progress: { done: ev.target.done, needed: ev.target.needed, met: ev.target.met, text: ev.target.text },
      lastInteractionAt: ev.inter.length ? ev.inter[ev.inter.length - 1].toISOString() : null,
      interactionsThisWeek: [...ev.days].filter((d) => d >= weekStart(ctx.today)).length,
      nextDate: ev.nextDate
        ? { id: ev.nextDate.d.id, kind: ev.nextDate.d.kind, label: this.dateLabel(ev.nextDate.d), date: ev.nextDate.next, daysAway: ev.nextDate.daysAway, sentence: this.dateSentence(r, ev.nextDate.d, ev.nextDate.daysAway) }
        : null,
      dates: dates.map((d) => ({ id: d.id, kind: d.kind, label: this.dateLabel(d), month: d.month, day: d.day, year: d.year, remindDaysBefore: d.remindDaysBefore })),
      openCommitments: ev.openCommitments.map((c) => this.commitmentDto(c)),
      loops: loops.map((l) => ({ id: l.id, title: l.title, waitingOnMe: ev.waitingLoops.some((w) => w.id === l.id), completedThisMonth: l.completedThisMonth, completedTotal: l.completedTotal })),
    };
  }

  targetText(r: Relationship): string | null {
    switch (r.targetCadence) {
      case 'DAILY': return r.relationshipType === 'PARTNER' ? 'Meaningful conversation every day' : 'Every day';
      case 'WEEKLY': return r.targetCount > 1 ? `At least ${r.targetCount} times a week` : 'At least once a week';
      case 'MONTHLY': return r.targetCount > 1 ? `At least ${r.targetCount} times a month` : 'At least once a month';
      case 'EVERY_N_DAYS': return r.category === 'PROFESSIONAL' ? `Follow up every ${r.targetEveryDays} days` : `Every ${r.targetEveryDays} days`;
      case 'WEEKDAY': return `${r.targetLabel || 'Check in'} every ${WEEKDAY_NAMES[r.targetWeekday ?? 1]}`;
      default: return null;
    }
  }

  commitmentDto(c: Commitment) {
    return {
      id: c.id, relationshipId: c.relationshipId, subjectUserId: c.subjectUserId, conversationId: c.conversationId,
      messageId: c.messageId, kind: c.kind, text: c.text, dueAt: c.dueAt.toISOString(), status: c.status,
      completedAt: c.completedAt?.toISOString() ?? null, createdAt: c.createdAt.toISOString(),
    };
  }

  private async loadAll(ownerId: string) {
    const [rels, dates, commitments, loops] = await Promise.all([
      this.relRepo.find({ where: { ownerUserId: ownerId }, order: { createdAt: 'ASC' } }),
      this.dateRepo.find({ where: { ownerUserId: ownerId } }),
      this.commitRepo.find({ where: { ownerUserId: ownerId, status: In(['OPEN', 'DONE']) } }),
      this.loops.listMine(ownerId),
    ]);
    return { rels, dates, commitments, loops };
  }

  private loopsWith(loops: LoopStateDto[], ownerId: string, subjectUserId: string | null) {
    if (!subjectUserId) return [];
    return loops.filter((l) => l.participants.length === 2 && l.participants.includes(subjectUserId) && l.participants.includes(ownerId));
  }

  /** The whole picture: the Connections screen, inbox chips and brief all come from here. */
  async overview(ownerId: string, tz?: string) {
    const settings = await this.settings(ownerId, tz);
    const now = new Date();
    const ctx: Ctx = { now, tz: settings.timezone, today: localDayKey(now, settings.timezone) };
    const { rels, dates, commitments, loops } = await this.loadAll(ownerId);

    const evals = [];
    for (const rel of rels) {
      const relLoops = this.loopsWith(loops, ownerId, rel.subjectUserId);
      evals.push(
        await this.evaluate(ownerId, rel, ctx, {
          dates: dates.filter((d) => d.relationshipId === rel.id),
          commitments: commitments.filter((c) => c.relationshipId === rel.id),
          loops: relLoops,
        }),
      );
    }

    const relationships = evals.map((ev) =>
      this.relationshipDto(ev, dates.filter((d) => d.relationshipId === ev.rel.id), this.loopsWith(loops, ownerId, ev.rel.subjectUserId), ctx),
    );

    // TODAY — who needs attention, most urgent first, one line per person.
    const today = evals
      .filter((ev) => ev.flags.some((f) => f.code !== 'DATE_SOON' || (ev.nextDate?.daysAway ?? 9) <= 1))
      .sort((a, b) => HEALTH_RANK[a.health.code] - HEALTH_RANK[b.health.code])
      .map((ev) => ({
        relationshipId: ev.rel.id,
        subjectUserId: ev.rel.subjectUserId,
        subjectPhone: ev.rel.subjectPhone,
        name: this.name(ev.rel),
        icon: RelationshipsService.icon(ev.rel),
        category: ev.rel.category,
        code: ev.health.code,
        text: ev.health.text,
        loopId: (ev.waitingLoops[0]?.id ?? null) as string | null,
        commitmentId: (ev.dueCommitments[0]?.id ?? null) as string | null,
      }));

    // Loops waiting with people the user hasn't classified.
    const coveredLoops = new Set(evals.flatMap((ev) => ev.waitingLoops.map((l) => l.id)));
    for (const l of loops) {
      if (coveredLoops.has(l.id) || !l.active || !l.periodKey || l.myAnswer || l.answeredBy.length === 0) continue;
      today.push({
        relationshipId: '', subjectUserId: l.participants.find((p) => p !== ownerId) ?? null, subjectPhone: null,
        name: l.title, icon: '🔁', category: 'PERSONAL', code: 'LOOP_WAITING', text: `${l.title} — your turn.`,
        loopId: l.id, commitmentId: null,
      });
    }

    // COMING UP — the next seven days.
    const comingUp: { date: string; daysAway: number; label: string; icon: string; text: string; relationshipId: string }[] = [];
    for (const ev of evals) {
      for (const u of ev.upcoming) {
        if (u.daysAway >= 1 && u.daysAway <= 7) {
          comingUp.push({ date: u.next, daysAway: u.daysAway, label: this.whenLabel(u.daysAway, u.next), icon: RelationshipsService.icon(ev.rel), text: `${this.dateLabel(u.d)} · ${this.name(ev.rel)}`, relationshipId: ev.rel.id });
        }
      }
      if (ev.rel.targetCadence === 'WEEKDAY') {
        const wd = ev.rel.targetWeekday ?? 1;
        const ahead = (wd - weekdayOf(ctx.today) + 7) % 7;
        if (ahead >= 1) {
          const date = addDays(ctx.today, ahead);
          comingUp.push({ date, daysAway: ahead, label: this.whenLabel(ahead, date), icon: RelationshipsService.icon(ev.rel), text: `${ev.rel.targetLabel || 'Check-in'} with ${this.name(ev.rel)}`, relationshipId: ev.rel.id });
        }
      }
      for (const c of ev.openCommitments) {
        const day = localDayKey(c.dueAt, ctx.tz);
        const away = daysBetween(ctx.today, day);
        if (away >= 1 && away <= 7) {
          comingUp.push({ date: day, daysAway: away, label: this.whenLabel(away, day), icon: '📌', text: `${c.text} · ${this.name(ev.rel)}`, relationshipId: ev.rel.id });
        }
      }
    }
    comingUp.sort((a, b) => a.daysAway - b.daysAway);

    // YOUR TARGETS — people on track, grouped the way people think.
    const groups: Record<string, { label: string; met: number; total: number }> = {
      FAMILY: { label: 'Family', met: 0, total: 0 },
      FRIENDS: { label: 'Friends', met: 0, total: 0 },
      PROFESSIONAL: { label: 'Professional', met: 0, total: 0 },
    };
    for (const ev of evals) {
      if (!ev.rel.targetCadence) continue;
      const g = ev.rel.category === 'PROFESSIONAL' ? 'PROFESSIONAL'
        : ['PARTNER', 'FAMILY', 'PARENT', 'CHILD', 'RELATIVE'].includes(ev.rel.relationshipType) ? 'FAMILY' : 'FRIENDS';
      groups[g].total += 1;
      if (ev.target.code === 'ON_TRACK') groups[g].met += 1;
    }

    const achievements = await this.evaluateAchievements(ownerId, evals, loops, commitments, dates, ctx);
    const profile = await this.profileRepo.findOne({ where: { userId: ownerId } });
    const firstName = (profile?.displayName || '').trim().split(/\s+/)[0] || '';

    return {
      today: ctx.today,
      timezone: ctx.tz,
      relationships,
      attention: today,
      comingUp: comingUp.slice(0, 12),
      targets: Object.values(groups).filter((g) => g.total > 0),
      achievements: achievements.all.slice(0, 20),
      newAchievements: achievements.fresh,
      achievementProgress: achievements.progress,
      brief: this.brief(firstName, today, ctx),
      moments: this.monthMoments(evals, loops, ctx),
    };
  }

  private whenLabel(daysAway: number, date: string): string {
    if (daysAway === 1) return 'Tomorrow';
    if (daysAway < 7) return WEEKDAY_NAMES[weekdayOf(date)];
    return dayMonthLabel(date);
  }

  /**
   * "18 moments shared this month" — progress framed as what was shared,
   * never as a streak that can be lost.
   */
  private monthMoments(evals: Awaited<ReturnType<RelationshipsService['evaluate']>>[], loops: LoopStateDto[], ctx: Ctx) {
    const month = ctx.today.slice(0, 7);
    const loopMoments = loops.reduce((n, l) => n + l.completedThisMonth, 0);
    const lines: string[] = [];
    if (loopMoments > 0) lines.push(`${loopMoments} ${loopMoments === 1 ? 'moment' : 'moments'} shared in Loops this month`);
    for (const ev of evals) {
      if (ev.rel.category !== 'PERSONAL') continue;
      const week = [...ev.days].filter((d) => d >= weekStart(ctx.today)).length;
      if (week >= 2) lines.push(`You checked in with ${this.name(ev.rel)} ${week} times this week`);
    }
    const thisMonthDays = evals.reduce((n, ev) => n + [...ev.days].filter((d) => d.startsWith(month)).length, 0);
    if (thisMonthDays > 0) lines.push(`${thisMonthDays} conversations with people who matter this month`);
    return lines.slice(0, 4);
  }

  /** One small morning summary instead of a string of separate nags. */
  private brief(firstName: string, attention: { icon: string; name: string; text: string; code: HealthCode }[], ctx: Ctx) {
    const hour = localParts(ctx.now, ctx.tz).hour;
    const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
    const people = attention.length;
    return {
      title: firstName ? `${greeting}, ${firstName}` : greeting,
      summary: people === 0
        ? 'Nobody needs your attention right now.'
        : `${people} ${people === 1 ? 'person' : 'people'} may need your attention today.`,
      lines: attention.slice(0, 3).map((a) => ({ icon: a.icon, name: a.name, text: a.text })),
    };
  }

  /**
   * Candidate notifications with stable keys and priorities. The device
   * applies quiet hours and daily caps and never shows the same key twice, so
   * an ignored reminder is not pushed again.
   */
  async nudges(ownerId: string, tz?: string) {
    const settings = await this.settings(ownerId, tz);
    const ov = await this.overview(ownerId, tz);
    const now = new Date();
    const ctx: Ctx = { now, tz: settings.timezone, today: localDayKey(now, settings.timezone) };
    const { rels, commitments, dates } = await this.loadAll(ownerId);
    const out: { key: string; priority: 'HIGH' | 'MEDIUM' | 'LOW'; kind: string; title: string; body: string; relationshipId: string | null; subjectUserId: string | null; notBefore: string | null }[] = [];
    const relById = new Map(rels.map((r) => [r.id, r]));
    const allowed = (r?: Relationship) =>
      !r || (r.remindersEnabled && (r.category === 'PROFESSIONAL' ? settings.professionalReminders : settings.personalReminders));

    // HIGH — a promise the user made, due now.
    for (const c of commitments.filter((c) => c.status === 'OPEN')) {
      const r = c.relationshipId ? relById.get(c.relationshipId) : undefined;
      const dueDay = localDayKey(c.dueAt, ctx.tz);
      if (dueDay > ctx.today) continue;
      out.push({
        key: `c:${c.id}:${dueDay}`, priority: 'HIGH', kind: 'COMMITMENT',
        title: r ? this.name(r) : 'Commitment', body: this.commitmentSentence(r, c, ctx),
        relationshipId: r?.id ?? null, subjectUserId: c.subjectUserId,
        notBefore: new Date(Math.min(c.dueAt.getTime(), now.getTime())).toISOString(),
      });
    }
    // MEDIUM — targets falling behind, Loops waiting.
    for (const rel of ov.relationships) {
      const r = relById.get(rel.id);
      if (!allowed(r)) continue;
      for (const f of rel.flags) {
        if (['FOLLOW_UP_DUE', 'DUE_TODAY', 'NEEDS_ATTENTION'].includes(f.code)) {
          out.push({ key: `t:${rel.id}:${ctx.today}`, priority: 'MEDIUM', kind: 'TARGET', title: rel.displayName || 'Check in', body: f.text, relationshipId: rel.id, subjectUserId: rel.subjectUserId, notBefore: null });
          break;
        }
      }
      if (settings.loopNotifications) {
        for (const f of rel.flags.filter((x) => x.code === 'LOOP_WAITING').slice(0, 1)) {
          out.push({ key: `l:${rel.id}:${ctx.today}`, priority: 'MEDIUM', kind: 'LOOP', title: 'Loop', body: f.text, relationshipId: rel.id, subjectUserId: rel.subjectUserId, notBefore: null });
        }
      }
    }
    // Important dates on their reminder day and on the day itself.
    if (settings.dateReminders) {
      for (const d of dates) {
        const r = relById.get(d.relationshipId);
        if (!r || !r.remindersEnabled) continue;
        const next = nextAnnual(ctx.today, d.month, d.day, d.year);
        if (!next) continue;
        const away = daysBetween(ctx.today, next);
        if (away === 0 || away === d.remindDaysBefore) {
          out.push({ key: `d:${d.id}:${next}:${away}`, priority: away === 0 ? 'HIGH' : 'MEDIUM', kind: 'DATE', title: this.dateLabel(d), body: this.dateSentence(r, d, away), relationshipId: r.id, subjectUserId: r.subjectUserId, notBefore: null });
        }
      }
    }
    // LOW — a newly earned achievement.
    if (settings.achievementNotifications) {
      // Anything earned in the last three days, not just on this call: the
      // Connections screen may have been the first to evaluate it. The device
      // shows each key once.
      const recent = ov.achievements.filter((a) => now.getTime() - new Date(a.unlockedAt).getTime() < 3 * 86_400_000);
      for (const a of recent) {
        out.push({ key: `a:${a.key}`, priority: 'LOW', kind: 'ACHIEVEMENT', title: a.title, body: a.detail, relationshipId: null, subjectUserId: null, notBefore: null });
      }
    }
    const caps: Record<string, number> = { LOW: 1, NORMAL: 2, HIGH: 4 };
    return {
      settings: {
        timezone: settings.timezone, quietStart: settings.quietStart, quietEnd: settings.quietEnd,
        briefEnabled: settings.briefEnabled, briefTime: settings.briefTime, dailyCap: caps[settings.frequency] ?? 2,
      },
      brief: ov.brief,
      nudges: out,
    };
  }

  // ---------------------------------------------------------- achievements

  private async evaluateAchievements(
    ownerId: string,
    evals: Awaited<ReturnType<RelationshipsService['evaluate']>>[],
    loops: LoopStateDto[],
    commitments: Commitment[],
    dates: ImportantDate[],
    ctx: Ctx,
  ) {
    const earned: { key: string; title: string; detail: string }[] = [];
    const lastMonthEnd = addDays(`${ctx.today.slice(0, 7)}-01`, -1);
    const lastMonth = lastMonthEnd.slice(0, 7);
    const lastMonthStart = `${lastMonth}-01`;
    const monthName = new Intl.DateTimeFormat('en-GB', { month: 'long', timeZone: 'UTC' }).format(new Date(`${lastMonthStart}T00:00:00Z`));

    for (const ev of evals) {
      const who = this.name(ev.rel);
      const inMonth = [...ev.days].filter((d) => d >= lastMonthStart && d <= lastMonthEnd);
      // Present — every week of last month had a check-in.
      if (ev.rel.category === 'PERSONAL' && inMonth.length > 0) {
        const weeks = new Set<string>();
        for (let k = lastMonthStart; k <= lastMonthEnd; k = addDays(k, 1)) weeks.add(isoWeekKey(k));
        const covered = new Set(inMonth.map((d) => isoWeekKey(d)));
        if ([...weeks].every((w) => covered.has(w))) {
          earned.push({ key: `present:${ev.rel.id}:${lastMonth}`, title: 'Present', detail: `You checked in with ${who} every week in ${monthName}.` });
        }
      }
      // Still Connected — in touch in each of the last six months.
      const months = new Set([...ev.days].map((d) => d.slice(0, 7)));
      let ok = true;
      let m = lastMonth;
      for (let i = 0; i < 6; i++) {
        if (!months.has(m)) { ok = false; break; }
        m = addDays(`${m}-01`, -1).slice(0, 7);
      }
      if (ok) earned.push({ key: `still_connected:${ev.rel.id}`, title: 'Still Connected', detail: `You and ${who} stayed in touch for six months running.` });
      // Our Moments — Loops completed together.
      const together = loops.filter((l) => ev.rel.subjectUserId && l.participants.includes(ev.rel.subjectUserId)).reduce((n, l) => n + l.completedTotal, 0);
      for (const milestone of [10, 50, 100]) {
        if (together >= milestone) earned.push({ key: `our_moments:${ev.rel.id}:${milestone}`, title: 'Our Moments', detail: `You and ${who} completed ${milestone} Loops.` });
      }
    }

    // Never Forgotten — every family date last month was marked with contact.
    const familyDates = dates.filter((d) => {
      const r = evals.find((e) => e.rel.id === d.relationshipId)?.rel;
      return r && r.category === 'PERSONAL' && +d.month === +lastMonth.slice(5, 7);
    });
    if (familyDates.length > 0) {
      const all = familyDates.every((d) => {
        const ev = evals.find((e) => e.rel.id === d.relationshipId)!;
        const k = `${lastMonth}-${String(d.day).padStart(2, '0')}`;
        return ev.days.has(k) || ev.days.has(addDays(k, -1));
      });
      if (all) earned.push({ key: `never_forgotten:${lastMonth}`, title: 'Never Forgotten', detail: `You remembered every important family date in ${monthName}.` });
    }

    // Good Listener — reciprocal voice Loops.
    const voice = await this.loops.voiceAnswers(ownerId);
    if (voice >= 20) earned.push({ key: 'good_listener:20', title: 'Good Listener', detail: 'You completed 20 reciprocal voice Loops.' });

    // Reliable — commitments kept on time.
    const onTime = commitments.filter((c) => c.status === 'DONE' && c.completedAt && c.completedAt.getTime() <= c.dueAt.getTime() + 6 * 3600_000).length;
    for (const n of [10, 25, 50]) {
      if (onTime >= n) earned.push({ key: `reliable:${n}`, title: 'Reliable', detail: `You fulfilled ${n} communication commitments on time.` });
    }

    // Professional month achievements.
    const pro = evals.filter((e) => e.rel.category === 'PROFESSIONAL' && e.rel.targetCadence);
    const kept = (e: typeof pro[number]) => {
      const inMonth = [...e.days].filter((d) => d >= lastMonthStart && d <= lastMonthEnd).sort();
      if (e.rel.targetCadence === 'EVERY_N_DAYS') {
        const n = e.rel.targetEveryDays || 7;
        const points = [addDays(lastMonthStart, -1), ...inMonth, addDays(lastMonthEnd, 1)];
        const before = [...e.days].filter((d) => d < lastMonthStart).sort().pop();
        if (before) points[0] = before;
        for (let i = 1; i < points.length; i++) if (daysBetween(points[i - 1], points[i]) > n) return false;
        return inMonth.length > 0;
      }
      if (e.rel.targetCadence === 'WEEKDAY' || e.rel.targetCadence === 'WEEKLY') {
        const weeks = new Set<string>();
        for (let k = lastMonthStart; k <= lastMonthEnd; k = addDays(k, 1)) weeks.add(isoWeekKey(k));
        const covered = new Set(inMonth.map((d) => isoWeekKey(d)));
        return [...weeks].every((w) => covered.has(w));
      }
      return inMonth.length >= (e.rel.targetCount || 1);
    };
    if (pro.length > 0 && pro.every(kept)) {
      earned.push({ key: `consistent_follow_up:${lastMonth}`, title: 'Consistent Follow-Up', detail: `You completed every scheduled professional follow-up in ${monthName}.` });
    }
    const team = pro.filter((e) => ['EMPLOYEE', 'COLLEAGUE', 'MANAGER'].includes(e.rel.relationshipType));
    if (team.length > 0 && team.every(kept)) {
      earned.push({ key: `connected_team:${lastMonth}`, title: 'Connected Team', detail: `You completed every team check-in in ${monthName}.` });
    }
    const clients = pro.filter((e) => ['CLIENT', 'CUSTOMER'].includes(e.rel.relationshipType));
    if (clients.length > 0 && clients.every(kept)) {
      earned.push({ key: `client_care:${lastMonth}`, title: 'Client Care', detail: `No important client went without contact in ${monthName}.` });
    }

    // ---- Early achievements: earned in days, not months.
    for (const ev of evals) {
      const who = this.name(ev.rel);
      const together = loops.filter((l) => ev.rel.subjectUserId && l.participants.includes(ev.rel.subjectUserId)).reduce((n, l) => n + l.completedTotal, 0);
      if (together >= 1) earned.push({ key: `first_moment:${ev.rel.id}`, title: 'First Moment', detail: `You and ${who} completed your first Loop together.` });
    }
    if (onTime >= 1) earned.push({ key: 'promise_kept:1', title: 'Promise Kept', detail: 'You kept your first commitment on time.' });

    // A Week Well Spent — every target met across last Monday–Sunday.
    const lastWeekStart = addDays(weekStart(ctx.today), -7);
    const lastWeekEnd = addDays(lastWeekStart, 6);
    const weekly = evals.filter((e) => e.rel.targetCadence && e.rel.targetCadence !== 'MONTHLY');
    const keptWeek = (e: typeof evals[number]) => {
      const inWeek = [...e.days].filter((d) => d >= lastWeekStart && d <= lastWeekEnd).sort();
      switch (e.rel.targetCadence) {
        case 'DAILY': return inWeek.length >= 7;
        case 'WEEKLY': return inWeek.length >= (e.rel.targetCount || 1);
        case 'WEEKDAY': {
          const day = addDays(lastWeekStart, ((e.rel.targetWeekday ?? 1) + 6) % 7);
          return e.days.has(day);
        }
        case 'EVERY_N_DAYS': {
          const n = e.rel.targetEveryDays || 7;
          const before = [...e.days].filter((d) => d < lastWeekStart).sort().pop();
          const points = [before ?? inWeek[0] ?? lastWeekStart, ...inWeek, addDays(lastWeekEnd, 1)];
          for (let i = 1; i < points.length; i++) if (daysBetween(points[i - 1], points[i]) > n) return false;
          return inWeek.length > 0 || (before !== undefined && daysBetween(before, lastWeekEnd) <= n);
        }
        default: return false;
      }
    };
    // Only for relationships that already existed before that week.
    const eligible = weekly.filter((e) => localDayKey(e.rel.createdAt, ctx.tz) <= lastWeekStart);
    if (eligible.length > 0 && eligible.every(keptWeek)) {
      earned.push({ key: `week_well_spent:${isoWeekKey(lastWeekStart)}`, title: 'A Week Well Spent', detail: 'You kept every target you set, all last week.' });
    }

    // ---- Progress towards the longer ones, so they are visible before they land.
    const progress: { key: string; title: string; detail: string; done: number; total: number }[] = [];
    const monthStart = `${ctx.today.slice(0, 7)}-01`;
    const monthWeeks = new Set<string>();
    for (let k = monthStart; k <= this.monthEnd(ctx.today); k = addDays(k, 1)) monthWeeks.add(isoWeekKey(k));
    for (const ev of evals) {
      const who = this.name(ev.rel);
      if (ev.rel.category === 'PERSONAL') {
        const covered = new Set([...ev.days].filter((d) => d >= monthStart).map((d) => isoWeekKey(d)));
        const done = [...monthWeeks].filter((w) => covered.has(w)).length;
        if (done > 0) progress.push({ key: `p:present:${ev.rel.id}`, title: 'Present', detail: `${who}: ${done} of ${monthWeeks.size} weeks this month`, done, total: monthWeeks.size });
      }
      let run = 0;
      let m = ctx.today.slice(0, 7);
      const months = new Set([...ev.days].map((d) => d.slice(0, 7)));
      while (months.has(m) && run < 6) {
        run++;
        m = addDays(`${m}-01`, -1).slice(0, 7);
      }
      if (run > 0 && run < 6) progress.push({ key: `p:still:${ev.rel.id}`, title: 'Still Connected', detail: `${who}: ${run} of 6 months in touch`, done: run, total: 6 });
      const together = loops.filter((l) => ev.rel.subjectUserId && l.participants.includes(ev.rel.subjectUserId)).reduce((n, l) => n + l.completedTotal, 0);
      const next = [10, 50, 100].find((x) => together < x);
      if (together > 0 && next) progress.push({ key: `p:moments:${ev.rel.id}`, title: 'Our Moments', detail: `${who}: ${together} of ${next} Loops`, done: together, total: next });
    }
    const nextReliable = [10, 25, 50].find((x) => onTime < x);
    if (onTime > 0 && nextReliable) progress.push({ key: 'p:reliable', title: 'Reliable', detail: `${onTime} of ${nextReliable} promises kept on time`, done: onTime, total: nextReliable });

    const existing = await this.achRepo.find({ where: { userId: ownerId } });
    const have = new Set(existing.map((a) => a.achievementKey));
    const fresh = earned.filter((e) => !have.has(e.key));
    if (fresh.length) {
      await this.achRepo.save(fresh.map((f) => this.achRepo.create({ userId: ownerId, achievementKey: f.key, title: f.title, detail: f.detail })));
    }
    const all = await this.achRepo.find({ where: { userId: ownerId }, order: { unlockedAt: 'DESC' } });
    return {
      all: all.map((a) => ({ key: a.achievementKey, title: a.title, detail: a.detail, unlockedAt: a.unlockedAt.toISOString(), shared: a.shared })),
      fresh,
      progress: progress.sort((a, b) => b.done / b.total - a.done / a.total).slice(0, 6),
    };
  }

  async shareAchievement(ownerId: string, key: string, shared: boolean) {
    await this.achRepo.update({ userId: ownerId, achievementKey: key }, { shared });
    return { ok: true };
  }

  // ---------------------------------------------------------- per person

  /** The context card inside a chat or contact page. */
  async forSubject(ownerId: string, subjectUserId: string | null, subjectPhone: string | null, tz?: string) {
    const ov = await this.overview(ownerId, tz);
    const rel = ov.relationships.find(
      (r) => (subjectUserId && r.subjectUserId === subjectUserId) || (subjectPhone && r.subjectPhone === subjectPhone),
    );
    return { relationship: rel ?? null };
  }

  /** Moments: a private timeline of what the two of them shared. */
  async timeline(ownerId: string, relationshipId: string, tz?: string) {
    const rel = await this.own(ownerId, relationshipId);
    const settings = await this.settings(ownerId, tz);
    const zone = settings.timezone;
    const today = localDayKey(new Date(), zone);
    const items: { at: string; day: string; kind: string; title: string; detail: string | null }[] = [];
    const push = (at: Date, kind: string, title: string, detail: string | null = null) =>
      items.push({ at: at.toISOString(), day: localDayKey(at, zone), kind, title, detail });

    for (const d of await this.dateRepo.find({ where: { relationshipId: rel.id } })) {
      const y = +today.slice(0, 4);
      for (const yy of [y, y - 1]) {
        if (d.year && d.year !== yy) continue;
        const k = `${yy}-${String(d.month).padStart(2, '0')}-${String(d.day).padStart(2, '0')}`;
        if (k <= today) {
          push(new Date(`${k}T12:00:00Z`), 'DATE', this.dateLabel(d));
          break;
        }
      }
    }
    for (const c of await this.commitRepo.find({ where: { ownerUserId: ownerId, relationshipId: rel.id } })) {
      push(c.createdAt, 'COMMITMENT', 'Commitment created', c.text);
      if (c.status === 'DONE' && c.completedAt) push(c.completedAt, 'COMMITMENT_DONE', rel.category === 'PROFESSIONAL' ? 'Follow-up completed' : 'Promise kept', c.text);
    }
    for (const ci of await this.checkinRepo.find({ where: { relationshipId: rel.id } })) push(ci.at, 'CHECKIN', 'Checked in', ci.note);

    if (rel.subjectUserId) {
      const calls: { at: Date; secs: number | null }[] = await this.relRepo.query(
        `SELECT answered_at AS at, EXTRACT(EPOCH FROM (ended_at - answered_at))::int AS secs FROM calls
         WHERE answered_at IS NOT NULL
           AND ((caller_user_id = $1 AND callee_user_id = $2) OR (caller_user_id = $2 AND callee_user_id = $1))
         ORDER BY answered_at DESC LIMIT 40`,
        [ownerId, rel.subjectUserId],
      );
      for (const c of calls) {
        const mins = c.secs && c.secs > 0 ? Math.max(1, Math.round(c.secs / 60)) : null;
        push(new Date(c.at), 'CALL', 'Voice call', mins ? `${mins} min` : null);
      }
      const [a, b] = [ownerId, rel.subjectUserId].sort();
      const conv: { id: string }[] = await this.relRepo.query('SELECT id FROM conversations WHERE dm_key = $1', [`${a}:${b}`]);
      if (conv[0]) {
        const photos: { at: Date; mine: boolean }[] = await this.relRepo.query(
          `SELECT created_at AS at, sender_user_id = $2 AS mine FROM messages
           WHERE conversation_id = $1 AND type = 'IMAGE' AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 20`,
          [conv[0].id, ownerId],
        );
        for (const p of photos) push(new Date(p.at), 'PHOTO', 'Shared photo');
        for (const l of await this.loops.completedIn(conv[0].id)) push(l.doneAt, 'LOOP', `${l.title} completed`);
        const first: { at: Date }[] = await this.relRepo.query(
          `SELECT MIN(created_at) AS at FROM messages WHERE conversation_id = $1 AND type <> 'SYSTEM'`,
          [conv[0].id],
        );
        if (first[0]?.at) push(new Date(first[0].at), 'FIRST', 'First conversation');
      }
    }
    items.sort((x, y) => (x.at < y.at ? 1 : -1));
    return { relationshipId: rel.id, items: items.slice(0, 80) };
  }
}
