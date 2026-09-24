/**
 * The rules for "about you": what can be said, how much of it, and who may
 * read it. Kept free of the database so every rule can be held to a test.
 */

export const TOPICS = ['INTERESTS', 'STRENGTHS', 'WEAKNESSES', 'FEARS', 'DREAMS', 'GOALS'] as const;
export type Topic = (typeof TOPICS)[number];

export const VISIBILITIES = ['EVERYONE', 'CONTACTS', 'NOBODY'] as const;
export type Visibility = (typeof VISIBILITIES)[number];

/**
 * The topics that make somebody easier to hurt.
 *
 * These can be shared with the people close to someone — that is much of the
 * point, since knowing what frightens a friend is how you avoid frightening
 * them — but never with everyone. "Everyone" is any stranger who finds the
 * profile, and a list of somebody's fears is precisely what a stranger who
 * means them harm would want. The database enforces this too.
 */
export const VULNERABLE: ReadonlySet<Topic> = new Set<Topic>(['FEARS', 'WEAKNESSES']);

/** At most this many entries per topic: a list of things, not an essay. */
export const MAX_ENTRIES = 8;
/** Long enough for "being alone in a big crowd"; short enough to stay a line. */
export const MAX_ENTRY_LENGTH = 80;

/**
 * The youngest anybody may be to tell Viro their date of birth.
 *
 * Thirteen is the most common floor worldwide (COPPA in the United States, and
 * the lowest age GDPR lets a country choose). It is a default rather than a
 * decision: the right number depends on where Viro operates and has to be set
 * by whoever owns that, in one place, here.
 */
export const MIN_AGE = 13;
/** Anything older than this is a typo, not a person. */
const MAX_AGE = 120;

export function isTopic(value: unknown): value is Topic {
  return typeof value === 'string' && (TOPICS as readonly string[]).includes(value);
}

export function isVisibility(value: unknown): value is Visibility {
  return typeof value === 'string' && (VISIBILITIES as readonly string[]).includes(value);
}

/**
 * Who sees a topic when the person has not said.
 *
 * Contacts for the things that help people get closer; only them for the
 * things that could be used against them. Opening those up is always the
 * person's own choice, never a default they did not notice.
 */
export function defaultVisibility(topic: Topic): Visibility {
  return VULNERABLE.has(topic) ? 'NOBODY' : 'CONTACTS';
}

/** Why a visibility is refused for a topic, or null if it is allowed. */
export function visibilityProblem(topic: Topic, visibility: Visibility): string | null {
  if (VULNERABLE.has(topic) && visibility === 'EVERYONE') {
    return 'This can be shared with your contacts, but not with everyone.';
  }
  return null;
}

/**
 * What somebody wrote, tidied.
 *
 * Trimmed, blanks dropped, repeats removed regardless of case, each line cut to
 * length and the list cut to size. Nothing is rewritten beyond that: these are
 * the person's own words, and the only edits allowed are the ones that keep the
 * list a list.
 */
export function cleanEntries(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of raw) {
    if (typeof item !== 'string') continue;
    const text = item.replace(/\s+/g, ' ').trim().slice(0, MAX_ENTRY_LENGTH).trim();
    if (!text) continue;
    const key = text.toLocaleLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(text);
    if (out.length >= MAX_ENTRIES) break;
  }
  return out;
}

/** Whole years between [birth] and [today], the way people count birthdays. */
export function ageOn(birth: Date, today: Date): number {
  let age = today.getUTCFullYear() - birth.getUTCFullYear();
  const before =
    today.getUTCMonth() < birth.getUTCMonth() ||
    (today.getUTCMonth() === birth.getUTCMonth() && today.getUTCDate() < birth.getUTCDate());
  if (before) age -= 1;
  return age;
}

/**
 * A date of birth read from "YYYY-MM-DD", or the reason it will not be kept.
 *
 * A date that says somebody is under [MIN_AGE] is refused rather than stored:
 * the moment Viro holds it, Viro knows it is holding a child's data, and it has
 * no policy for doing that safely. Refusing is the honest answer until it does.
 */
export function readBirthDate(raw: string, today: Date): { date: Date } | { problem: string } {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(raw.trim());
  if (!match) return { problem: 'That date is not in the right form.' };
  const [, y, m, d] = match;
  const date = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)));
  // A date that rolled over — the 31st of February — is not a date.
  if (
    date.getUTCFullYear() !== Number(y) ||
    date.getUTCMonth() !== Number(m) - 1 ||
    date.getUTCDate() !== Number(d)
  ) {
    return { problem: 'That date does not exist.' };
  }
  if (date.getTime() > today.getTime()) return { problem: 'That date is in the future.' };
  const age = ageOn(date, today);
  if (age > MAX_AGE) return { problem: 'That date is too long ago.' };
  if (age < MIN_AGE) return { problem: `You need to be at least ${MIN_AGE} to add a date of birth.` };
  return { date };
}

/**
 * The part of a date of birth that may be shared: the day and the month.
 *
 * Never the year. The year is how old somebody is, and saying so is theirs.
 */
export function birthdayOf(date: Date | string | null | undefined): string | null {
  if (!date) return null;
  const d = typeof date === 'string' ? new Date(`${date.slice(0, 10)}T00:00:00Z`) : date;
  if (Number.isNaN(d.getTime())) return null;
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(d.getUTCDate()).padStart(2, '0');
  return `${mm}-${dd}`;
}
