import {
  addDays, daysBetween, isoWeekKey, localDayKey, localParts, nextAnnual, weekStart, weekdayOf,
} from './local-time';
import { LoopsService } from './loops.service';

describe('local-time', () => {
  it('reads the local day in the user zone, not UTC', () => {
    // 23:30 UTC on 19 Sep is already 20 Sep in Lusaka (UTC+2).
    const d = new Date('2026-09-19T23:30:00Z');
    expect(localDayKey(d, 'UTC')).toBe('2026-09-19');
    expect(localDayKey(d, 'Africa/Lusaka')).toBe('2026-09-20');
    expect(localParts(d, 'Africa/Lusaka').hour).toBe(1);
  });

  it('does day arithmetic across month and year ends', () => {
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(daysBetween('2026-09-19', '2026-09-26')).toBe(7);
  });

  it('knows weekdays and ISO weeks (Monday start)', () => {
    expect(weekdayOf('2026-09-19')).toBe(6); // Saturday
    expect(weekStart('2026-09-19')).toBe('2026-09-14');
    expect(weekStart('2026-09-14')).toBe('2026-09-14');
    expect(isoWeekKey('2026-09-19')).toBe('2026-W38');
    expect(isoWeekKey('2027-01-01')).toBe('2026-W53');
  });

  it('finds the next yearly occurrence, and one-off dates only once', () => {
    expect(nextAnnual('2026-09-19', 9, 20)).toBe('2026-09-20');
    expect(nextAnnual('2026-09-19', 9, 19)).toBe('2026-09-19');
    expect(nextAnnual('2026-09-19', 2, 14)).toBe('2027-02-14');
    expect(nextAnnual('2026-09-19', 3, 1, 2026)).toBeNull();
    // 29 Feb falls back to the 28th in a common year.
    expect(nextAnnual('2026-09-19', 2, 29)).toBe('2027-02-28');
  });
});

describe('Loop periods', () => {
  const at = new Date('2026-09-19T10:00:00Z'); // Saturday in Lusaka
  const loop = (frequency: string, daysMask: number | null = null) => ({ frequency, daysMask, timezone: 'Africa/Lusaka' });

  it('daily Loops open every day', () => {
    expect(LoopsService.periodKey(loop('DAILY'), at)).toBe('2026-09-19');
  });

  it('weekday Loops rest at the weekend', () => {
    expect(LoopsService.periodKey(loop('WEEKDAYS'), at)).toBeNull();
    expect(LoopsService.periodKey(loop('WEEKDAYS'), new Date('2026-09-18T10:00:00Z'))).toBe('2026-09-18');
  });

  it('custom days follow the mask (Sunday = bit 0)', () => {
    const saturdayOnly = 1 << 6;
    expect(LoopsService.periodKey(loop('CUSTOM', saturdayOnly), at)).toBe('2026-09-19');
    expect(LoopsService.periodKey(loop('CUSTOM', 1 << 1), at)).toBeNull();
  });

  it('weekly and monthly Loops share one period across their span', () => {
    expect(LoopsService.periodKey(loop('WEEKLY'), at)).toBe('2026-W38');
    expect(LoopsService.periodKey(loop('MONTHLY'), at)).toBe('2026-09');
    expect(LoopsService.periodKey(loop('ONCE'), at)).toBe('once');
  });
});
