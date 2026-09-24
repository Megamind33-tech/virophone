import {
  ageOn,
  birthdayOf,
  cleanEntries,
  defaultVisibility,
  MAX_ENTRIES,
  MAX_ENTRY_LENGTH,
  MIN_AGE,
  readBirthDate,
  visibilityProblem,
} from './about-you';

const today = new Date(Date.UTC(2026, 8, 23)); // 23 September 2026

describe('who may read what somebody says about themselves', () => {
  it('keeps fears and weaknesses away from everyone', () => {
    // "Everyone" is any stranger who finds the profile. A list of somebody's
    // fears is exactly what a stranger who means them harm would want.
    expect(visibilityProblem('FEARS', 'EVERYONE')).not.toBeNull();
    expect(visibilityProblem('WEAKNESSES', 'EVERYONE')).not.toBeNull();
    // Sharing them with the people close to you is allowed — that is how a
    // friend learns what not to do.
    expect(visibilityProblem('FEARS', 'CONTACTS')).toBeNull();
    expect(visibilityProblem('FEARS', 'NOBODY')).toBeNull();
  });

  it('lets the rest be as open as the person likes', () => {
    for (const topic of ['INTERESTS', 'STRENGTHS', 'DREAMS', 'GOALS'] as const) {
      expect(visibilityProblem(topic, 'EVERYONE')).toBeNull();
    }
  });

  it('starts the vulnerable topics private and the rest with contacts', () => {
    expect(defaultVisibility('FEARS')).toBe('NOBODY');
    expect(defaultVisibility('WEAKNESSES')).toBe('NOBODY');
    expect(defaultVisibility('INTERESTS')).toBe('CONTACTS');
    expect(defaultVisibility('GOALS')).toBe('CONTACTS');
  });
});

describe('what somebody wrote', () => {
  it('is tidied, not rewritten', () => {
    expect(cleanEntries(['  Cooking  ', 'long   walks', '', '   '])).toEqual(['Cooking', 'long walks']);
  });

  it('drops repeats whatever their case', () => {
    expect(cleanEntries(['Music', 'music', 'MUSIC', 'Films'])).toEqual(['Music', 'Films']);
  });

  it('stays a list of lines rather than an essay', () => {
    const many = Array.from({ length: 20 }, (_, i) => `thing ${i}`);
    expect(cleanEntries(many)).toHaveLength(MAX_ENTRIES);
    expect(cleanEntries(['x'.repeat(500)])[0]).toHaveLength(MAX_ENTRY_LENGTH);
  });

  it('ignores anything that is not text', () => {
    expect(cleanEntries(['ok', 3, null, { a: 1 }])).toEqual(['ok']);
    expect(cleanEntries('not a list')).toEqual([]);
  });
});

describe('a date of birth', () => {
  it('counts age the way birthdays are counted', () => {
    expect(ageOn(new Date(Date.UTC(2000, 8, 23)), today)).toBe(26); // today is the birthday
    expect(ageOn(new Date(Date.UTC(2000, 8, 24)), today)).toBe(25); // tomorrow is
  });

  it('is kept when it is real and old enough', () => {
    expect(readBirthDate('1995-04-12', today)).toEqual({ date: new Date(Date.UTC(1995, 3, 12)) });
  });

  it('is refused for a child rather than stored', () => {
    // Holding it would mean knowingly holding a child's data with no policy
    // for doing that safely.
    const young = readBirthDate(`${2026 - MIN_AGE + 1}-01-01`, today);
    expect('problem' in young).toBe(true);
    const exactly = readBirthDate(`${2026 - MIN_AGE}-09-23`, today);
    expect('date' in exactly).toBe(true);
  });

  it('refuses dates that are not dates', () => {
    expect('problem' in readBirthDate('2001-02-31', today)).toBe(true);
    expect('problem' in readBirthDate('31/01/2001', today)).toBe(true);
    expect('problem' in readBirthDate('2030-01-01', today)).toBe(true);
    expect('problem' in readBirthDate('1850-01-01', today)).toBe(true);
  });

  it('shares only the day and month, never the year', () => {
    expect(birthdayOf(new Date(Date.UTC(1995, 3, 12)))).toBe('04-12');
    expect(birthdayOf('1995-04-12')).toBe('04-12');
    expect(birthdayOf(null)).toBeNull();
  });
});
