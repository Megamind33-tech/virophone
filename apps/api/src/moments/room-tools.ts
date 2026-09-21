/**
 * Small things people do together inside a room, beside whatever the room is:
 * a kitchen timer everyone can see, and a question everyone can answer.
 *
 * Like the room itself, each is one small record held in Redis, changed
 * whole, with a revision, and announced to everyone. Nothing here is written
 * to the database: it matters while the Moment lasts and not after.
 */

export class ToolError extends Error {}

// ------------------------------------------------------------------ timer

export const TIMER_OPS = ['START', 'PAUSE', 'RESUME', 'ADD', 'CANCEL'] as const;
export type TimerOp = (typeof TIMER_OPS)[number];

export interface MomentTimer {
  momentId: string;
  revision: number;
  status: 'NONE' | 'RUNNING' | 'PAUSED';
  label: string | null;
  /** Server ms when it reaches zero; set while running. Phones count down to it. */
  endsAt: number | null;
  /** Time left while paused. */
  remainingMs: number | null;
  durationMs: number | null;
  updatedBy: string | null;
}

export interface TimerChange { op: TimerOp; durationMs?: number; label?: string; addMs?: number }

const MAX_TIMER_MS = 4 * 60 * 60 * 1000;

export function noTimer(momentId: string): MomentTimer {
  return { momentId, revision: 0, status: 'NONE', label: null, endsAt: null, remainingMs: null, durationMs: null, updatedBy: null };
}

export function applyTimer(t: MomentTimer, change: TimerChange, by: string, now: number): MomentTimer {
  const next = (patch: Partial<MomentTimer>): MomentTimer => ({ ...t, ...patch, revision: t.revision + 1, updatedBy: by });
  switch (change.op) {
    case 'START': {
      const ms = Math.round(change.durationMs ?? 0);
      if (!Number.isFinite(ms) || ms < 5_000 || ms > MAX_TIMER_MS) throw new ToolError('A timer runs from 5 seconds to 4 hours.');
      const label = change.label?.trim().slice(0, 40) || null;
      return next({ status: 'RUNNING', label, durationMs: ms, endsAt: now + ms, remainingMs: null });
    }
    case 'PAUSE':
      if (t.status !== 'RUNNING' || t.endsAt == null) throw new ToolError('The timer is not running.');
      return next({ status: 'PAUSED', remainingMs: Math.max(0, t.endsAt - now), endsAt: null });
    case 'RESUME':
      if (t.status !== 'PAUSED' || t.remainingMs == null) throw new ToolError('The timer is not paused.');
      return next({ status: 'RUNNING', endsAt: now + t.remainingMs, remainingMs: null });
    case 'ADD': {
      const add = Math.round(change.addMs ?? 0);
      if (!Number.isFinite(add) || add <= 0 || add > 60 * 60 * 1000) throw new ToolError('Add up to an hour at a time.');
      if (t.status === 'RUNNING' && t.endsAt != null) {
        // A timer that has already gone off starts counting again from now.
        return next({ endsAt: Math.min(Math.max(t.endsAt, now) + add, now + MAX_TIMER_MS) });
      }
      if (t.status === 'PAUSED' && t.remainingMs != null) return next({ remainingMs: Math.min(t.remainingMs + add, MAX_TIMER_MS) });
      throw new ToolError('There is no timer to add to.');
    }
    case 'CANCEL':
      return next({ status: 'NONE', label: null, endsAt: null, remainingMs: null, durationMs: null });
    default:
      throw new ToolError('That is not something a timer does.');
  }
}

// ----------------------------------------------------------------- choice

export const CHOICE_OPS = ['ASK', 'PICK', 'DECIDE', 'CLEAR'] as const;
export type ChoiceOp = (typeof CHOICE_OPS)[number];

export interface ChoiceOption { id: string; text: string }

export interface MomentChoice {
  momentId: string;
  revision: number;
  status: 'NONE' | 'OPEN' | 'DECIDED';
  question: string | null;
  options: ChoiceOption[];
  /** Who picked what: a small room, so everyone sees everyone's answer. */
  picks: Record<string, string>;
  askedBy: string | null;
  decided: string | null;
  updatedBy: string | null;
}

export interface ChoiceChange { op: ChoiceOp; question?: string; options?: string[]; optionId?: string | null }

export function noChoice(momentId: string): MomentChoice {
  return { momentId, revision: 0, status: 'NONE', question: null, options: [], picks: {}, askedBy: null, decided: null, updatedBy: null };
}

export function applyChoice(c: MomentChoice, change: ChoiceChange, by: string): MomentChoice {
  const next = (patch: Partial<MomentChoice>): MomentChoice => ({ ...c, ...patch, revision: c.revision + 1, updatedBy: by });
  switch (change.op) {
    case 'ASK': {
      const question = change.question?.trim().slice(0, 120);
      if (!question) throw new ToolError('Ask a question.');
      const texts = (change.options ?? []).map((o) => String(o ?? '').trim().slice(0, 60)).filter((o) => o.length > 0);
      const unique = texts.filter((o, i) => texts.findIndex((x) => x.toLowerCase() === o.toLowerCase()) === i);
      if (unique.length < 2 || unique.length > 6) throw new ToolError('Give two to six different options.');
      return next({
        status: 'OPEN', question, options: unique.map((text, i) => ({ id: String(i + 1), text })),
        picks: {}, askedBy: by, decided: null,
      });
    }
    case 'PICK': {
      if (c.status !== 'OPEN') throw new ToolError('Nothing is being asked right now.');
      const picks = { ...c.picks };
      if (change.optionId == null) delete picks[by];
      else if (c.options.some((o) => o.id === change.optionId)) picks[by] = change.optionId;
      else throw new ToolError('That is not one of the options.');
      return next({ picks });
    }
    case 'DECIDE': {
      if (c.status !== 'OPEN') throw new ToolError('Nothing is being asked right now.');
      // Whoever asked decides; the answer is theirs to take.
      if (c.askedBy !== by) throw new ToolError('Only the person who asked can decide.');
      if (!c.options.some((o) => o.id === change.optionId)) throw new ToolError('That is not one of the options.');
      return next({ status: 'DECIDED', decided: change.optionId ?? null });
    }
    case 'CLEAR':
      return next({ status: 'NONE', question: null, options: [], picks: {}, askedBy: null, decided: null });
    default:
      throw new ToolError('That is not something a question does.');
  }
}

/** Someone left: their answer leaves with them. */
export function withoutPicksOf(c: MomentChoice, userId: string): MomentChoice | null {
  if (!(userId in c.picks)) return null;
  const picks = { ...c.picks };
  delete picks[userId];
  return { ...c, picks, revision: c.revision + 1, updatedBy: null };
}

// ------------------------------------------------------------------ touch

/** Small ways to reach someone in the room without words. */
export const TOUCH_KINDS = ['HEART', 'HUG', 'WAVE', 'TAP'] as const;
export type TouchKind = (typeof TOUCH_KINDS)[number];
