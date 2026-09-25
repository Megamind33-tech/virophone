jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { MomentsService } from '../../src/moments/moments.service';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string; deviceId: string };

/**
 * Stage E: what a Moment leaves behind, which by default is nothing.
 *
 * The room is erased when a Moment ends — that promise is unchanged, and these
 * tests are mostly about proving it still holds: the messages, the files and
 * the room state all go, and what survives is only what somebody deliberately
 * kept, in metadata, about a Moment they were actually in.
 */
describe('Moment keepsakes: an ending that leaves nothing unless asked', () => {
  let app: INestApplication;
  let db: Client;
  let a: User; let b: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });
  const create = async (u = a, overrides = {}) =>
    (await http().post('/api/v1/moments').set(auth(u))
      .send({ type: 'FREE', visibility: 'CONNECTIONS', durationMinutes: 15, intent: 'COOK', ...overrides })
      .expect(201)).body;
  const join = async (u: User, id: string) =>
    (await http().post(`/api/v1/moments/${id}/join`).set(auth(u)).expect(200)).body;
  const endingOf = async (u: User, id: string, status = 200) =>
    (await http().get(`/api/v1/moments/${id}/keepsakes`).set(auth(u)).expect(status)).body;
  const keptBy = async (u: User) =>
    (await http().get('/api/v1/moments/keepsakes').set(auth(u)).expect(200)).body.keepsakes;

  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId, code: '123456',
      devicePublicKey: `keep-${n}`, platform: 'ANDROID', appVersion: 'keep-test',
    }).expect(201)).body;
  }

  beforeAll(async () => {
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    a = await register('+260978740001');
    b = await register('+260978740002');
    stranger = await register('+260978740003');
    await db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`,
      [a.userId, b.userId]);
  }, 120000);
  afterAll(async () => { if (app) await app.close(); if (db) await db.end(); });
  beforeEach(async () => {
    await db.query('DELETE FROM moments');
    await db.query('DELETE FROM moment_keepsakes');
    await db.query('DELETE FROM moment_keepsake_offers');
    await db.query('DELETE FROM moment_keepsake_audience');
    await db.query(`UPDATE viro_connections SET status = 'ACCEPTED'`);
  });

  it('offers the Moment itself, and says who it was with and for how long', async () => {
    const m = await create();
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);

    const ending = await endingOf(b, m.id);
    expect(ending.withPeople).toEqual(['Viro user']); // no display name set in this fixture
    expect(ending.togetherMs).toBeGreaterThanOrEqual(0);
    expect(ending.offers).toHaveLength(1);
    expect(ending.offers[0].kind).toBe('MOMENT');
    expect(ending.offers[0].title).toBe('Cooking together');
    expect(ending.offers[0].kept).toBe(false);
  });

  it('uses what the host called it when they gave it a name', async () => {
    const m = await create(a, { type: 'CUSTOM', text: 'Sunday rice', intent: 'COOK' });
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const ending = await endingOf(b, m.id);
    expect(ending.offers[0].title).toBe('Sunday rice');
  });

  it('offers a decision that was actually decided, and not one still open', async () => {
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/choice`).set(auth(a))
      .send({ op: 'ASK', question: 'Which rice?', options: ['Jollof', 'Plain'] }).expect(200);
    // Still open at this point: an undecided question is not a memory.
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    let ending = await endingOf(b, m.id);
    expect(ending.offers.map((o: any) => o.kind)).toEqual(['MOMENT']);

    // Now one that was decided.
    const m2 = await create();
    await join(b, m2.id);
    await http().post(`/api/v1/moments/${m2.id}/choice`).set(auth(a))
      .send({ op: 'ASK', question: 'Which rice?', options: ['Jollof', 'Plain'] }).expect(200);
    await http().post(`/api/v1/moments/${m2.id}/choice`).set(auth(a))
      .send({ op: 'DECIDE', optionId: '1' }).expect(200);
    await http().delete(`/api/v1/moments/${m2.id}`).set(auth(a)).expect(200);
    ending = await endingOf(b, m2.id);
    const decision = ending.offers.find((o: any) => o.kind === 'DECISION');
    expect(decision.title).toBe('Which rice?');
    expect(decision.detail).toBe('Jollof');
  });

  it('keeps nothing by itself: an ending nobody answers leaves no keepsake', async () => {
    const m = await create();
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    expect(await keptBy(a)).toEqual([]);
    expect(await keptBy(b)).toEqual([]);
    // An empty answer is a real answer and still keeps nothing.
    await http().post(`/api/v1/moments/${m.id}/keepsakes`).set(auth(b)).send({ offerIds: [] }).expect(200);
    expect(await keptBy(b)).toEqual([]);
  });

  it('keeps exactly what one person chose, without touching the other', async () => {
    const m = await create();
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const ending = await endingOf(b, m.id);

    await http().post(`/api/v1/moments/${m.id}/keepsakes`).set(auth(b))
      .send({ offerIds: [ending.offers[0].id] }).expect(200);

    const mine = await keptBy(b);
    expect(mine).toHaveLength(1);
    expect(mine[0].title).toBe('Cooking together');
    expect(mine[0].withPeople).toEqual(['Viro user']);
    // The other person kept nothing, and keeping is not contagious.
    expect(await keptBy(a)).toEqual([]);
    // The ending now knows it was kept, so it is not offered again.
    expect((await endingOf(b, m.id)).offers[0].kept).toBe(true);
  });

  it('keeping the same thing twice is one keepsake', async () => {
    const m = await create();
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const ending = await endingOf(b, m.id);
    const body = { offerIds: [ending.offers[0].id] };
    await http().post(`/api/v1/moments/${m.id}/keepsakes`).set(auth(b)).send(body).expect(200);
    await http().post(`/api/v1/moments/${m.id}/keepsakes`).set(auth(b)).send(body).expect(200);
    expect(await keptBy(b)).toHaveLength(1);
  });

  it('never shows an ending to somebody who was not there', async () => {
    const m = await create();
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    await endingOf(stranger, m.id, 404);
    const ending = await endingOf(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/keepsakes`).set(auth(stranger))
      .send({ offerIds: [ending.offers[0].id] }).expect(404);
    await http().get(`/api/v1/moments/${m.id}/keepsakes`).expect(401);
  });

  it('offers nothing for a Moment somebody spent alone', async () => {
    const m = await create();
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    await endingOf(a, m.id, 404);
  });

  it('still erases the room: the memory is metadata, not a recording', async () => {
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a))
      .send({ body: 'this must not survive' }).expect(201);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);

    for (const table of ['moment_messages', 'moment_participants', 'moment_media']) {
      const left = await db.query(`SELECT count(*)::int AS n FROM ${table} WHERE moment_id = $1`, [m.id]);
      expect(left.rows[0].n).toBe(0);
    }
    // What survived is an offer, and it holds no message.
    const offers = await db.query(`SELECT title FROM moment_keepsake_offers WHERE moment_id = $1`, [m.id]);
    expect(offers.rows).toHaveLength(1);
  });

  it('stores what it keeps encrypted at rest when a key is configured', async () => {
    const m = await create(a, { type: 'CUSTOM', text: 'Sunday rice', intent: 'COOK' });
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const stored = await db.query(`SELECT title FROM moment_keepsake_offers WHERE moment_id = $1`, [m.id]);
    const raw = stored.rows[0].title as string;
    if (process.env.MESSAGE_ENCRYPTION_KEY) {
      expect(raw.startsWith('v1.')).toBe(true);
      expect(raw).not.toContain('Sunday rice');
    }
    // Either way it reads back as itself.
    expect((await endingOf(b, m.id)).offers[0].title).toBe('Sunday rice');
  });

  it('sweeps an ending nobody answered, and leaves an answered one alone', async () => {
    const answered = await create();
    await join(b, answered.id);
    await http().delete(`/api/v1/moments/${answered.id}`).set(auth(a)).expect(200);
    const ending = await endingOf(b, answered.id);
    await http().post(`/api/v1/moments/${answered.id}/keepsakes`).set(auth(b))
      .send({ offerIds: [ending.offers[0].id] }).expect(200);

    const ignored = await create(b);
    await join(a, ignored.id);
    await http().delete(`/api/v1/moments/${ignored.id}`).set(auth(b)).expect(200);

    // Age every offer past its expiry and sweep.
    await db.query(`UPDATE moment_keepsake_offers SET expires_at = now() - interval '1 minute'`);
    await app.get(MomentsService).sweepKeepsakeOffers();

    const leftBehind = await db.query(
      `SELECT count(*)::int AS n FROM moment_keepsake_audience WHERE moment_id = $1`, [ignored.id]);
    expect(leftBehind.rows[0].n).toBe(0);
    // The kept one survives, and is still readable.
    expect(await keptBy(b)).toHaveLength(1);
    // Its ending is over, though: there is nothing left to offer.
    expect((await endingOf(b, answered.id)).offers).toEqual([]);
  });

  it('lets somebody drop their own keepsake, and nobody else theirs', async () => {
    const m = await create();
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const ending = await endingOf(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/keepsakes`).set(auth(b))
      .send({ offerIds: [ending.offers[0].id] }).expect(200);
    const [kept] = await keptBy(b);

    await http().delete(`/api/v1/moments/keepsakes/${kept.id}`).set(auth(a)).expect(404);
    await http().delete(`/api/v1/moments/keepsakes/${kept.id}`).set(auth(b)).expect(200);
    expect(await keptBy(b)).toEqual([]);
  });

  it('a kept Moment and a kept message both turn up in the relationship story', async () => {
    const m = await create();
    await join(b, m.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const ending = await endingOf(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/keepsakes`).set(auth(b))
      .send({ offerIds: [ending.offers[0].id] }).expect(200);

    const sent = await http().post('/api/v1/messages').set(auth(a))
      .send({ toUserId: b.userId, body: 'Remember this', clientMsgId: `keep-${Date.now()}` }).expect(201);
    await http().put(`/api/v1/messages/${sent.body.message.id}/star`).set(auth(b)).expect(200);

    const rel = await http().put('/api/v1/relationships').set(auth(b)).send({
      subjectUserId: a.userId, displayName: 'Alex', relationshipType: 'FRIEND',
    }).expect(200);
    const tl = await http().get(`/api/v1/relationships/${rel.body.id}/timeline`).set(auth(b)).expect(200);
    const kinds = tl.body.items.map((i: any) => i.kind);
    expect(kinds).toContain('KEEPSAKE');
    expect(kinds).toContain('KEPT_MESSAGE');
    const keepsake = tl.body.items.find((i: any) => i.kind === 'KEEPSAKE');
    expect(keepsake.detail).toBe('Cooking together');
    // A stranger's timeline never sees b's kept things.
    const none = await http().put('/api/v1/relationships').set(auth(stranger)).send({
      subjectUserId: a.userId, displayName: 'A', relationshipType: 'FRIEND',
    }).expect(200);
    const tl2 = await http().get(`/api/v1/relationships/${none.body.id}/timeline`).set(auth(stranger)).expect(200);
    expect(tl2.body.items.some((i: any) => i.kind === 'KEEPSAKE' || i.kind === 'KEPT_MESSAGE')).toBe(false);
  });
});
