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

jest.setTimeout(60000);
type User = { userId: string; accessToken: string; deviceId: string };

/**
 * Intimate signals: a presence event between two connected people. Not a
 * message — it never becomes chat content, it is never shown to anyone else,
 * and a burst of taps is one stronger signal rather than five interruptions.
 */
describe('Intimate signals', () => {
  let app: INestApplication;
  let db: Client;
  let a: User; let b: User; let stranger: User; let d: User; let e: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });
  const connect = async (x: string, y: string) =>
    db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`, [x, y]);

  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId, code: '123456',
      devicePublicKey: `sig-${n}`, platform: 'ANDROID', appVersion: 'sig-test',
    }).expect(201)).body;
  }

  beforeAll(async () => {
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    a = await register('+260978750001');
    b = await register('+260978750002');
    stranger = await register('+260978750003');
    d = await register('+260978750004');
    e = await register('+260978750005');
    await connect(a.userId, b.userId);
    await connect(d.userId, e.userId);
  });

  afterAll(async () => {
    await app.close();
    await db.end();
  });

  const rows = async (sender: string, recipient: string, kind: string) =>
    (await db.query(
      `SELECT * FROM intimate_signals WHERE sender_user_id=$1 AND recipient_user_id=$2 AND kind=$3`,
      [sender, recipient, kind],
    )).rows;

  it('reaches a connected person once, and a burst becomes one stronger signal', async () => {
    const first = await http().post('/api/v1/signals').set(auth(a)).send({ toUserId: b.userId, kind: 'THINKING_OF_YOU' });
    expect(first.status).toBe(201);
    expect(first.body.count).toBe(1);

    await http().post('/api/v1/signals').set(auth(a)).send({ toUserId: b.userId, kind: 'THINKING_OF_YOU' }).expect(201);
    const third = await http().post('/api/v1/signals').set(auth(a)).send({ toUserId: b.userId, kind: 'THINKING_OF_YOU' }).expect(201);
    expect(third.body.count).toBe(3);
    expect(third.body.todayCount).toBe(3);

    // Three taps, one row, one wake-up — not three interruptions.
    const stored = await rows(a.userId, b.userId, 'THINKING_OF_YOU');
    expect(stored).toHaveLength(1);
    expect(stored[0].count).toBe(3);
  });

  it('never reaches a stranger or past a block', async () => {
    await http().post('/api/v1/signals').set(auth(stranger)).send({ toUserId: b.userId, kind: 'HERE' }).expect(403);
    await db.query(`INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2)`, [b.userId, a.userId]);
    await http().post('/api/v1/signals').set(auth(a)).send({ toUserId: b.userId, kind: 'MISS_YOU' }).expect(403);
    await db.query(`DELETE FROM blocks WHERE blocker_user_id=$1`, [b.userId]);
  });

  it('knows when both people reached for each other', async () => {
    const back = await http().post('/api/v1/signals').set(auth(b)).send({ toUserId: a.userId, kind: 'THINKING_OF_YOU' }).expect(201);
    expect(back.body.mutual).toBe(true);
    const mine = await rows(a.userId, b.userId, 'THINKING_OF_YOU');
    const theirs = await rows(b.userId, a.userId, 'THINKING_OF_YOU');
    expect(mine[0].mutual).toBe(true);
    expect(theirs[0].mutual).toBe(true);
  });

  it('the phone can acknowledge and answer, and only the recipient may', async () => {
    const [row] = await rows(a.userId, b.userId, 'THINKING_OF_YOU');
    await http().post(`/api/v1/signals/${row.id}/ack`).set(auth(b)).send({ state: 'PRESENTED' }).expect(201);
    await http().post(`/api/v1/signals/${row.id}/respond`).set(auth(a)).send({ kind: 'PROUD' }).expect(201);
    let after = (await db.query(`SELECT state, responded_kind FROM intimate_signals WHERE id=$1`, [row.id])).rows[0];
    // The sender cannot answer their own signal: the acknowledgement stands.
    expect(after.state).toBe('PRESENTED');
    expect(after.responded_kind).toBe(null);

    await http().post(`/api/v1/signals/${row.id}/respond`).set(auth(b)).send({ kind: 'MADE_ME_SMILE' }).expect(201);
    after = (await db.query(`SELECT state, responded_kind FROM intimate_signals WHERE id=$1`, [row.id])).rows[0];
    expect(after.state).toBe('RESPONDED');
    expect(after.responded_kind).toBe('MADE_ME_SMILE');
  });

  it('a flood is refused: a signal is a touch, not a buzzer', async () => {
    const burst = 12;
    const codes: number[] = [];
    for (let i = 0; i < burst; i++) {
      const r = await http().post('/api/v1/signals').set(auth(d)).send({ toUserId: e.userId, kind: 'HOLD_ME' });
      codes.push(r.status);
    }
    expect(codes.filter((c) => c === 429).length).toBeGreaterThan(0);
    const stored = (await db.query(
      `SELECT COALESCE(SUM(count),0) AS total FROM intimate_signals WHERE sender_user_id=$1 AND recipient_user_id=$2`,
      [d.userId, e.userId],
    )).rows[0].total;
    expect(Number(stored)).toBeLessThanOrEqual(10);
  });
});
