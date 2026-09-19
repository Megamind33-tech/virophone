import { Injectable, HttpStatus } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import { Loop, LoopAnswer } from '../database/entities/relationship.entity';
import { MediaObject } from '../database/entities/messaging-extras.entity';
import { Profile } from '../database/entities/profile.entity';
import { MessagesService } from '../messages/messages.service';
import { PushService } from '../push/push.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { isoWeekKey, localDayKey, localParts, safeZone } from './local-time';

export const LOOP_FREQUENCIES = ['DAILY', 'WEEKDAYS', 'WEEKLY', 'MONTHLY', 'CUSTOM', 'ONCE'];
export const LOOP_RESPONSE_KINDS = ['ANY', 'TEXT', 'VOICE', 'PHOTO', 'EMOJI', 'CHOICE'];

export interface CreateLoopInput {
  conversationId?: string;
  toUserId?: string;
  title: string;
  prompt: string;
  frequency: string;
  daysMask?: number | null;
  timeOfDay?: string;
  timezone?: string;
  responseKind?: string;
  choices?: string[] | null;
  reciprocal?: boolean;
}

export interface LoopAnswerDto {
  id: string;
  userId: string;
  kind: string;
  text: string | null;
  media: ReturnType<MessagesService['mediaDto']> | null;
  createdAt: string;
}

export interface LoopStateDto {
  id: string;
  conversationId: string;
  createdBy: string;
  title: string;
  prompt: string;
  frequency: string;
  daysMask: number | null;
  timeOfDay: string;
  timezone: string;
  responseKind: string;
  choices: string[] | null;
  reciprocal: boolean;
  active: boolean;
  participants: string[];
  /** null when the Loop has no occurrence today (a weekend on a weekday Loop). */
  periodKey: string | null;
  answeredBy: string[];
  waitingFor: string[];
  myAnswer: LoopAnswerDto | null;
  /** Answers I may see: all of them once I have answered (reciprocal), else only mine. */
  answers: LoopAnswerDto[];
  revealed: boolean;
  completedTotal: number;
  completedThisMonth: number;
}

@Injectable()
export class LoopsService {
  constructor(
    @InjectRepository(Loop) private readonly loopRepo: Repository<Loop>,
    @InjectRepository(LoopAnswer) private readonly answerRepo: Repository<LoopAnswer>,
    @InjectRepository(MediaObject) private readonly mediaRepo: Repository<MediaObject>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    private readonly messages: MessagesService,
    private readonly push: PushService,
  ) {}

  private fail(code: string, message: string, status: HttpStatus): never {
    throw new ViroException(code as never, message, status);
  }

  /** Which occurrence is current at [now], or null if none today. */
  static periodKey(loop: Pick<Loop, 'frequency' | 'daysMask' | 'timezone'>, now = new Date()): string | null {
    const tz = safeZone(loop.timezone);
    const today = localDayKey(now, tz);
    const wd = localParts(now, tz).weekday;
    switch (loop.frequency) {
      case 'ONCE':
        return 'once';
      case 'DAILY':
        return today;
      case 'WEEKDAYS':
        return wd >= 1 && wd <= 5 ? today : null;
      case 'CUSTOM':
        return (loop.daysMask ?? 0) & (1 << wd) ? today : null;
      case 'WEEKLY':
        return isoWeekKey(today);
      case 'MONTHLY':
        return today.slice(0, 7);
      default:
        return today;
    }
  }

  async create(userId: string, input: CreateLoopInput): Promise<LoopStateDto> {
    const title = (input.title || '').trim().slice(0, 80);
    const prompt = (input.prompt || '').trim().slice(0, 280);
    if (!title || !prompt) this.fail('VALIDATION_ERROR', 'A Loop needs a name and a question.', HttpStatus.BAD_REQUEST);
    if (!LOOP_FREQUENCIES.includes(input.frequency)) {
      this.fail('VALIDATION_ERROR', 'Unknown frequency.', HttpStatus.BAD_REQUEST);
    }
    const responseKind = input.responseKind && LOOP_RESPONSE_KINDS.includes(input.responseKind) ? input.responseKind : 'ANY';
    if (responseKind === 'CHOICE' && (!input.choices || input.choices.filter((c) => c.trim()).length < 2)) {
      this.fail('VALIDATION_ERROR', 'Give at least two choices.', HttpStatus.BAD_REQUEST);
    }
    if (input.frequency === 'CUSTOM' && !input.daysMask) {
      this.fail('VALIDATION_ERROR', 'Pick at least one day.', HttpStatus.BAD_REQUEST);
    }
    const timeOfDay = /^\d{2}:\d{2}$/.test(input.timeOfDay || '') ? input.timeOfDay! : '19:00';

    let conversationId = input.conversationId;
    if (!conversationId) {
      if (!input.toUserId) this.fail('VALIDATION_ERROR', 'Choose who the Loop is with.', HttpStatus.BAD_REQUEST);
      conversationId = (await this.messages.getOrCreateDm(userId, input.toUserId)).id;
    }
    await this.messages.assertMember(conversationId, userId);

    const loop = await this.loopRepo.save(
      this.loopRepo.create({
        conversationId,
        createdBy: userId,
        title,
        prompt,
        frequency: input.frequency,
        daysMask: input.daysMask ?? null,
        timeOfDay,
        timezone: safeZone(input.timezone),
        responseKind,
        choices: responseKind === 'CHOICE' ? input.choices!.map((c) => c.trim()).filter(Boolean).slice(0, 6) : null,
        reciprocal: input.reciprocal !== false,
        active: true,
      }),
    );
    await this.messages.postSystem(conversationId, userId, 'loop_created', `started a Loop: ${title}`);
    await this.notifyChanged(loop);
    return this.state(userId, loop);
  }

  private async loopFor(userId: string, loopId: string): Promise<Loop> {
    const loop = await this.loopRepo.findOne({ where: { id: loopId } });
    if (!loop) this.fail('NOT_FOUND', 'Loop not found.', HttpStatus.NOT_FOUND);
    await this.messages.assertMember(loop.conversationId, userId);
    return loop;
  }

  async update(userId: string, loopId: string, patch: Partial<CreateLoopInput> & { active?: boolean }) {
    const loop = await this.loopFor(userId, loopId);
    if (patch.active !== undefined) loop.active = !!patch.active;
    // Wording and schedule are the creator's to change; pausing is anyone's.
    if (loop.createdBy === userId) {
      if (patch.title?.trim()) loop.title = patch.title.trim().slice(0, 80);
      if (patch.prompt?.trim()) loop.prompt = patch.prompt.trim().slice(0, 280);
      if (patch.timeOfDay && /^\d{2}:\d{2}$/.test(patch.timeOfDay)) loop.timeOfDay = patch.timeOfDay;
      if (patch.frequency && LOOP_FREQUENCIES.includes(patch.frequency)) loop.frequency = patch.frequency;
      if (patch.daysMask !== undefined) loop.daysMask = patch.daysMask;
    }
    loop.updatedAt = new Date();
    await this.loopRepo.save(loop);
    await this.notifyChanged(loop);
    return this.state(userId, loop);
  }

  async remove(userId: string, loopId: string) {
    const loop = await this.loopFor(userId, loopId);
    if (loop.createdBy !== userId) this.fail('FORBIDDEN', 'Only whoever started the Loop can delete it.', HttpStatus.FORBIDDEN);
    const participants = await this.messages.participantIds(loop.conversationId);
    await this.loopRepo.delete({ id: loop.id });
    await this.messages.emitFrame(participants, { type: 'loop.removed', conversationId: loop.conversationId, loopId: loop.id });
    return { ok: true };
  }

  async answer(userId: string, loopId: string, input: { kind: string; text?: string; mediaId?: string }) {
    const loop = await this.loopFor(userId, loopId);
    if (!loop.active) this.fail('VALIDATION_ERROR', 'This Loop is paused.', HttpStatus.BAD_REQUEST);
    const periodKey = LoopsService.periodKey(loop);
    if (!periodKey) this.fail('VALIDATION_ERROR', "This Loop isn't open today.", HttpStatus.BAD_REQUEST);

    const kind = (input.kind || '').toUpperCase();
    if (!['TEXT', 'VOICE', 'PHOTO', 'EMOJI', 'CHOICE'].includes(kind)) {
      this.fail('VALIDATION_ERROR', 'Unsupported answer.', HttpStatus.BAD_REQUEST);
    }
    if (loop.responseKind !== 'ANY' && loop.responseKind !== kind) {
      this.fail('VALIDATION_ERROR', 'This Loop asks for a different kind of answer.', HttpStatus.BAD_REQUEST);
    }
    const text = (input.text || '').trim().slice(0, 1000) || null;
    if ((kind === 'TEXT' || kind === 'EMOJI' || kind === 'CHOICE') && !text) {
      this.fail('VALIDATION_ERROR', 'Your answer is empty.', HttpStatus.BAD_REQUEST);
    }
    if (kind === 'CHOICE' && !(loop.choices ?? []).includes(text!)) {
      this.fail('VALIDATION_ERROR', 'Pick one of the choices.', HttpStatus.BAD_REQUEST);
    }
    if ((kind === 'VOICE' || kind === 'PHOTO') && !input.mediaId) {
      this.fail('VALIDATION_ERROR', 'Attach your recording or photo.', HttpStatus.BAD_REQUEST);
    }
    if (input.mediaId) {
      const media = await this.mediaRepo.findOne({ where: { id: input.mediaId } });
      if (!media || media.ownerUserId !== userId) this.fail('VALIDATION_ERROR', 'Attachment not found.', HttpStatus.BAD_REQUEST);
    }

    const participants = await this.messages.participantIds(loop.conversationId);
    const already = await this.answerRepo.find({ where: { loopId: loop.id, periodKey } });
    const wasComplete = participants.every((p) => already.some((a) => a.userId === p));
    if (wasComplete) this.fail('VALIDATION_ERROR', 'Everyone has already answered this one.', HttpStatus.CONFLICT);

    const existing = already.find((a) => a.userId === userId);
    const answer = await this.answerRepo.save(
      this.answerRepo.create({
        ...(existing ? { id: existing.id } : {}),
        loopId: loop.id,
        periodKey,
        userId,
        kind,
        text,
        mediaId: input.mediaId ?? null,
      }),
    );
    const answered = new Set([...already.map((a) => a.userId), userId]);
    const complete = participants.every((p) => answered.has(p));
    const others = participants.filter((p) => p !== userId);
    const name = await this.nameOf(userId);

    if (complete) {
      await this.messages.postSystemLoop(loop.conversationId, userId, {
        event: 'loop_completed',
        loopId: loop.id,
        periodKey,
        title: loop.title,
      });
      await this.push.sendToUsers(others, {
        title: `${name} completed your Loop ❤️`,
        body: `${loop.title} — both answers are revealed.`,
        data: { type: 'loop', loopId: loop.id, conversationId: loop.conversationId },
      });
    } else if (!loop.reciprocal) {
      await this.messages.postSystemLoop(loop.conversationId, userId, {
        event: 'loop_answer',
        loopId: loop.id,
        periodKey,
        answerId: answer.id,
        title: loop.title,
      });
    } else if (!existing) {
      await this.push.sendToUsers(others, {
        title: `${name} answered ${loop.title}`,
        body: 'Answer too, and you will both see each other’s.',
        data: { type: 'loop', loopId: loop.id, conversationId: loop.conversationId },
      });
    }
    await this.notifyChanged(loop);
    return this.state(userId, loop);
  }

  private async nameOf(userId: string): Promise<string> {
    const p = await this.profileRepo.findOne({ where: { userId } });
    return p?.displayName?.trim() || 'Someone';
  }

  private async notifyChanged(loop: Loop) {
    const participants = await this.messages.participantIds(loop.conversationId);
    await this.messages.emitFrame(participants, {
      type: 'loop.updated',
      conversationId: loop.conversationId,
      loopId: loop.id,
    });
  }

  private async answerDtos(answers: LoopAnswer[]): Promise<LoopAnswerDto[]> {
    const mediaIds = answers.map((a) => a.mediaId).filter((x): x is string => !!x);
    const media = mediaIds.length ? await this.mediaRepo.find({ where: { id: In(mediaIds) } }) : [];
    return answers.map((a) => {
      const mo = a.mediaId ? media.find((m) => m.id === a.mediaId) : undefined;
      return {
        id: a.id,
        userId: a.userId,
        kind: a.kind,
        text: a.text,
        media: mo ? this.messages.mediaDto(mo) : null,
        createdAt: a.createdAt.toISOString(),
      };
    });
  }

  /** Counts periods that every participant answered. */
  private async completedPeriods(loopIds: string[], participantCount: number): Promise<{ loop_id: string; period_key: string; done_at: Date }[]> {
    if (loopIds.length === 0) return [];
    return this.answerRepo.query(
      `SELECT loop_id, period_key, MAX(created_at) AS done_at FROM loop_answers
       WHERE loop_id = ANY($1) GROUP BY loop_id, period_key HAVING COUNT(DISTINCT user_id) >= $2`,
      [loopIds, participantCount],
    );
  }

  async state(userId: string, loop: Loop, now = new Date()): Promise<LoopStateDto> {
    const participants = await this.messages.participantIds(loop.conversationId);
    const periodKey = LoopsService.periodKey(loop, now);
    const answers = periodKey ? await this.answerRepo.find({ where: { loopId: loop.id, periodKey } }) : [];
    const mine = answers.find((a) => a.userId === userId) ?? null;
    const answeredBy = answers.map((a) => a.userId);
    const revealed = participants.every((p) => answeredBy.includes(p));
    const canSeeOthers = !loop.reciprocal || !!mine || revealed;
    const visible = answers.filter((a) => a.userId === userId || canSeeOthers);
    const dtos = await this.answerDtos(visible);
    const done = await this.completedPeriods([loop.id], participants.length);
    const month = localDayKey(now, loop.timezone).slice(0, 7);
    return {
      id: loop.id,
      conversationId: loop.conversationId,
      createdBy: loop.createdBy,
      title: loop.title,
      prompt: loop.prompt,
      frequency: loop.frequency,
      daysMask: loop.daysMask,
      timeOfDay: loop.timeOfDay,
      timezone: loop.timezone,
      responseKind: loop.responseKind,
      choices: loop.choices,
      reciprocal: loop.reciprocal,
      active: loop.active,
      participants,
      periodKey,
      answeredBy,
      waitingFor: periodKey ? participants.filter((p) => !answeredBy.includes(p)) : [],
      myAnswer: mine ? dtos.find((d) => d.id === mine.id) ?? null : null,
      answers: dtos,
      revealed,
      completedTotal: done.length,
      completedThisMonth: done.filter((d) => localDayKey(new Date(d.done_at), loop.timezone).startsWith(month)).length,
    };
  }

  async listForConversation(userId: string, conversationId: string): Promise<LoopStateDto[]> {
    await this.messages.assertMember(conversationId, userId);
    const loops = await this.loopRepo.find({ where: { conversationId }, order: { createdAt: 'ASC' } });
    return Promise.all(loops.map((l) => this.state(userId, l)));
  }

  /** Every Loop the user takes part in, for Connections and reminders. */
  async listMine(userId: string): Promise<LoopStateDto[]> {
    const rows: { id: string }[] = await this.loopRepo.query(
      `SELECT l.id FROM loops l JOIN conversation_participants p ON p.conversation_id = l.conversation_id
       WHERE p.user_id = $1 ORDER BY l.created_at ASC`,
      [userId],
    );
    if (rows.length === 0) return [];
    const loops = await this.loopRepo.find({ where: { id: In(rows.map((r) => r.id)) } });
    return Promise.all(loops.map((l) => this.state(userId, l)));
  }

  /** Past occurrences, newest first. Reciprocal answers only once revealed. */
  async history(userId: string, loopId: string, limit = 30) {
    const loop = await this.loopFor(userId, loopId);
    const participants = await this.messages.participantIds(loop.conversationId);
    const answers = await this.answerRepo.find({ where: { loopId: loop.id }, order: { createdAt: 'DESC' } });
    const byPeriod = new Map<string, LoopAnswer[]>();
    for (const a of answers) byPeriod.set(a.periodKey, [...(byPeriod.get(a.periodKey) ?? []), a]);
    const out = [];
    for (const [periodKey, list] of [...byPeriod.entries()].slice(0, limit)) {
      const complete = participants.every((p) => list.some((a) => a.userId === p));
      const mine = list.some((a) => a.userId === userId);
      const visible = list.filter((a) => a.userId === userId || !loop.reciprocal || complete || mine);
      out.push({ periodKey, complete, answers: await this.answerDtos(visible) });
    }
    return out;
  }

  /** Completed Loop occurrences in a conversation — feeds Moments and achievements. */
  async completedIn(conversationId: string) {
    const loops = await this.loopRepo.find({ where: { conversationId } });
    const participants = await this.messages.participantIds(conversationId);
    const done = await this.completedPeriods(loops.map((l) => l.id), participants.length);
    return done.map((d) => ({
      loopId: d.loop_id,
      title: loops.find((l) => l.id === d.loop_id)?.title ?? 'Loop',
      periodKey: d.period_key,
      doneAt: new Date(d.done_at),
    }));
  }

  /** How many reciprocal Loops [userId] answered by voice — "Good Listener". */
  async voiceAnswers(userId: string): Promise<number> {
    const rows: { n: string }[] = await this.answerRepo.query(
      `SELECT COUNT(*) AS n FROM loop_answers a JOIN loops l ON l.id = a.loop_id
       WHERE a.user_id = $1 AND a.kind = 'VOICE' AND l.reciprocal = TRUE`,
      [userId],
    );
    return parseInt(rows[0]?.n ?? '0', 10);
  }

  /** May [userId] fetch this Loop attachment? Mirrors the reveal rule. */
  async mayAccessAnswerMedia(userId: string, mediaId: string): Promise<boolean> {
    const answer = await this.answerRepo.findOne({ where: { mediaId } });
    if (!answer) return false;
    if (answer.userId === userId) return true;
    const loop = await this.loopRepo.findOne({ where: { id: answer.loopId } });
    if (!loop || !(await this.messages.isMember(loop.conversationId, userId))) return false;
    if (!loop.reciprocal) return true;
    return !!(await this.answerRepo.findOne({ where: { loopId: loop.id, periodKey: answer.periodKey, userId } }));
  }
}
