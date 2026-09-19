/**
 * Calendar arithmetic in the user's own time zone. "Today", "this week" and
 * "every Friday" only mean something locally; the server runs in UTC.
 */

export interface LocalParts {
  year: number;
  month: number; // 1-12
  day: number;
  weekday: number; // 0 = Sunday
  hour: number;
  minute: number;
}

const WEEKDAYS: Record<string, number> = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };

export function safeZone(tz: string | undefined | null): string {
  if (!tz) return 'Africa/Lusaka';
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
    return tz;
  } catch {
    return 'Africa/Lusaka';
  }
}

export function localParts(date: Date, tz: string): LocalParts {
  const f = new Intl.DateTimeFormat('en-US', {
    timeZone: safeZone(tz),
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    weekday: 'short',
    hour: 'numeric',
    minute: 'numeric',
    hourCycle: 'h23',
  });
  const p: Record<string, string> = {};
  for (const part of f.formatToParts(date)) p[part.type] = part.value;
  return {
    year: parseInt(p.year, 10),
    month: parseInt(p.month, 10),
    day: parseInt(p.day, 10),
    weekday: WEEKDAYS[p.weekday] ?? 0,
    hour: parseInt(p.hour, 10) % 24,
    minute: parseInt(p.minute, 10),
  };
}

const pad = (n: number) => String(n).padStart(2, '0');

/** 'YYYY-MM-DD' of [date] in [tz]. */
export function localDayKey(date: Date, tz: string): string {
  const p = localParts(date, tz);
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

/** Whole days between two local day keys (b - a). */
export function daysBetween(a: string, b: string): number {
  const ta = Date.UTC(+a.slice(0, 4), +a.slice(5, 7) - 1, +a.slice(8, 10));
  const tb = Date.UTC(+b.slice(0, 4), +b.slice(5, 7) - 1, +b.slice(8, 10));
  return Math.round((tb - ta) / 86_400_000);
}

/** Adds [n] days to a local day key. */
export function addDays(key: string, n: number): string {
  const t = Date.UTC(+key.slice(0, 4), +key.slice(5, 7) - 1, +key.slice(8, 10)) + n * 86_400_000;
  const d = new Date(t);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/** Weekday (0 = Sunday) of a local day key. */
export function weekdayOf(key: string): number {
  return new Date(Date.UTC(+key.slice(0, 4), +key.slice(5, 7) - 1, +key.slice(8, 10))).getUTCDay();
}

/** ISO week key, e.g. '2026-W38', of a local day key. Weeks start on Monday. */
export function isoWeekKey(key: string): string {
  const d = new Date(Date.UTC(+key.slice(0, 4), +key.slice(5, 7) - 1, +key.slice(8, 10)));
  const day = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  const week = Math.ceil(((d.getTime() - yearStart.getTime()) / 86_400_000 + 1) / 7);
  return `${d.getUTCFullYear()}-W${pad(week)}`;
}

/** The Monday that starts the week containing [key]. */
export function weekStart(key: string): string {
  const wd = weekdayOf(key);
  return addDays(key, -((wd + 6) % 7));
}

export const WEEKDAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
export const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/** "20 September" */
export function dayMonthLabel(key: string): string {
  return `${+key.slice(8, 10)} ${MONTH_NAMES[+key.slice(5, 7) - 1]}`;
}

/** Minutes since midnight for 'HH:MM'. */
export function minutesOf(hhmm: string): number {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm || '');
  if (!m) return 0;
  return Math.min(23, +m[1]) * 60 + Math.min(59, +m[2]);
}

/** The next occurrence (as a local day key, today included) of month/day. */
export function nextAnnual(todayKey: string, month: number, day: number, year?: number | null): string | null {
  const y = +todayKey.slice(0, 4);
  const mk = (yy: number) => `${yy}-${pad(month)}-${pad(Math.min(day, daysInMonth(yy, month)))}`;
  if (year) {
    const k = mk(year);
    return k >= todayKey ? k : null;
  }
  const thisYear = mk(y);
  return thisYear >= todayKey ? thisYear : mk(y + 1);
}

export function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}
