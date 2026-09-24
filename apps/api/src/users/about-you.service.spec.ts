import { AboutYouService } from './about-you.service';

/**
 * A stand-in for the table, answering the handful of queries the service
 * makes. Enough to hold the service to its promises without a database.
 */
function fakeDb() {
  const rows = new Map<string, { topic: string; entries: string[]; visibility: string }[]>();
  const of = (userId: string) => rows.get(userId) ?? [];
  const db = {
    rows,
    query: jest.fn(async (sql: string, params: unknown[]) => {
      const [userId, topic, entries, visibility] = params as [string, string, string[], string];
      if (sql.startsWith('SELECT topic, entries, visibility FROM profile_topics WHERE user_id = $1')) {
        return of(userId);
      }
      if (sql.includes('cardinality(entries) > 0')) {
        return of(userId).filter((r) => r.entries.length > 0);
      }
      if (sql.startsWith('SELECT visibility FROM profile_topics')) {
        return of(userId).filter((r) => r.topic === topic);
      }
      if (sql.startsWith('DELETE FROM profile_topics WHERE user_id = $1 AND topic = $2')) {
        rows.set(userId, of(userId).filter((r) => r.topic !== topic));
        return [];
      }
      if (sql.startsWith('DELETE FROM profile_topics WHERE user_id = $1')) {
        rows.delete(userId);
        return [];
      }
      if (sql.startsWith('INSERT INTO profile_topics')) {
        rows.set(userId, [...of(userId).filter((r) => r.topic !== topic), { topic, entries, visibility }]);
        return [];
      }
      throw new Error(`unexpected query: ${sql}`);
    }),
  };
  return db;
}

/** Contacts are whoever this set says; nobody else. */
function fakeVisibility(contacts: [string, string][]) {
  const are = (a: string, b: string) => contacts.some(([x, y]) => (x === a && y === b) || (x === b && y === a));
  return {
    canSee: jest.fn(async (viewer: string, owner: string, setting: string) => {
      if (viewer === owner) return true;
      if (setting === 'NOBODY') return false;
      if (setting === 'EVERYONE') return true;
      return are(viewer, owner);
    }),
  };
}

describe('what other people are shown of somebody', () => {
  const setup = () => {
    const db = fakeDb();
    const service = new AboutYouService(db as never, fakeVisibility([['mosty', 'natasha']]) as never);
    return { db, service };
  };

  it('shows a contact what is shared with contacts, and a stranger nothing of it', async () => {
    const { service } = setup();
    await service.set('mosty', 'INTERESTS', ['Cooking'], 'CONTACTS');
    expect(await service.visibleTo('natasha', 'mosty')).toEqual([{ topic: 'INTERESTS', entries: ['Cooking'] }]);
    expect(await service.visibleTo('stranger', 'mosty')).toEqual([]);
  });

  it('keeps fears private until the person opens them up', async () => {
    const { service } = setup();
    // No visibility given: the vulnerable default is "only me".
    await service.set('mosty', 'FEARS', ['Being alone in a crowd']);
    expect(await service.visibleTo('natasha', 'mosty')).toEqual([]);
    // Shared with contacts on purpose, a contact sees it — that is the point.
    await service.set('mosty', 'FEARS', ['Being alone in a crowd'], 'CONTACTS');
    expect(await service.visibleTo('natasha', 'mosty')).toEqual([
      { topic: 'FEARS', entries: ['Being alone in a crowd'] },
    ]);
    expect(await service.visibleTo('stranger', 'mosty')).toEqual([]);
  });

  it('refuses to put fears or weaknesses in front of everyone', async () => {
    const { service } = setup();
    await expect(service.set('mosty', 'FEARS', ['Heights'], 'EVERYONE')).rejects.toThrow();
    await expect(service.set('mosty', 'WEAKNESSES', ['Mornings'], 'EVERYONE')).rejects.toThrow();
    expect(await service.visibleTo('stranger', 'mosty')).toEqual([]);
  });

  it('keeps an earlier choice when only the words change', async () => {
    const { service } = setup();
    await service.set('mosty', 'GOALS', ['Finish my course'], 'NOBODY');
    await service.set('mosty', 'GOALS', ['Finish my course', 'Run 5k']);
    const mine = await service.mine('mosty');
    expect(mine.find((t) => t.topic === 'GOALS')).toMatchObject({ visibility: 'NOBODY', entries: ['Finish my course', 'Run 5k'] });
  });

  it('treats an emptied topic as unanswered rather than as an empty choice', async () => {
    const { db, service } = setup();
    await service.set('mosty', 'DREAMS', ['The sea']);
    await service.set('mosty', 'DREAMS', ['   ']);
    expect(db.rows.get('mosty')?.some((r) => r.topic === 'DREAMS')).toBe(false);
  });

  it('shows its owner every topic, answered or not, with how each is set', async () => {
    const { service } = setup();
    const mine = await service.mine('mosty');
    expect(mine.map((t) => t.topic)).toEqual(['INTERESTS', 'STRENGTHS', 'WEAKNESSES', 'FEARS', 'DREAMS', 'GOALS']);
    expect(mine.find((t) => t.topic === 'FEARS')?.visibility).toBe('NOBODY');
    expect(mine.find((t) => t.topic === 'INTERESTS')?.visibility).toBe('CONTACTS');
  });

  it('leaves nothing behind when the account goes', async () => {
    const { service } = setup();
    await service.set('mosty', 'FEARS', ['Heights'], 'CONTACTS');
    await service.wipe('mosty');
    expect(await service.visibleTo('natasha', 'mosty')).toEqual([]);
    expect((await service.mine('mosty')).every((t) => t.entries.length === 0)).toBe(true);
  });

  it('refuses a topic that is not one of the six', async () => {
    const { service } = setup();
    await expect(service.set('mosty', 'PASSWORDS', ['nope'])).rejects.toThrow();
  });
});
