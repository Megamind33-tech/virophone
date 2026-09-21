jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

/**
 * Moment interaction, stage D: doing things together inside a room.
 *
 * A cooking night between two phones on the real socket: music beside the
 * cooking, one kitchen timer both can see and change, a hug felt on the other
 * phone, and a question both can answer. Nothing here is stored beyond the
 * Moment, and touches are not stored at all.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import * as WebSocket from 'ws';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { RedisService } from '../../src/redis/redis.service';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string };

describe('Doing things together in a Moment', () => {
  let app: INestApplication;
  let db: Client;
  let natasha: User; let mosty: User; let chipo: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });

  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({ challengeId: otp.body.challengeId,
      code: '123456', devicePublicKey: `together-${n}`, platform: 'ANDROID', appVersion: 'together-test' }).expect(201)).body;
  }
  const start = async (intent = 'COOK') => (await http().post('/api/v1/moments').set(auth(natasha))
    .send({ type: 'FREE', intent, visibility: 'CONNECTIONS', durationMinutes: 60 }).expect(201)).body;
  const join = (u: User, id: string) => http().post(`/api/v1/moments/${id}/join`).set(auth(u)).expect(200);
  const timer = (u: User, id: string, body: Record<string, unknown>, status = 200) =>
    http().post(`/api/v1/moments/${id}/timer`).set(auth(u)).send(body).expect(status);
  const choice = (u: User, id: string, body: Record<string, unknown>, status = 200) =>
    http().post(`/api/v1/moments/${id}/choice`).set(auth(u)).send(body).expect(status);
  const touch = (u: User, id: string, body: Record<string, unknown>, status = 200) =>
    http().post(`/api/v1/moments/${id}/touch`).set(auth(u)).send(body).expect(status);

  async function phone(u: User) {
    const port = app.getHttpServer().address().port;
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${u.accessToken}`);
    const frames: any[] = [];
    socket.on('message', (raw) => frames.push(JSON.parse(raw.toString())));
    await new Promise<void>((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
    await new Promise((r) => setTimeout(r, 100));
    const waitFor = async (match: (f: any) => boolean) => {
      for (let i = 0; i < 200; i++) {
        const found = frames.find(match);
        if (found) return found;
        await new Promise((r) => setTimeout(r, 20));
      }
      throw new Error('Frame never arrived');
    };
    return { socket, frames, waitFor };
  }

  beforeAll(async () => {
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    natasha = await register('+260978760001'); mosty = await register('+260978760002');
    chipo = await register('+260978760003'); stranger = await register('+260978760004');
    for (const u of [mosty, chipo]) {
      await db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`,
        [natasha.userId, u.userId]);
    }
  }, 120000);
  afterAll(async () => { if (app) await app.close(); if (db) await db.end(); });
  beforeEach(async () => {
    await db.query('DELETE FROM moments');
    // Each test starts with nobody having touched anyone lately.
    for (const u of [natasha, mosty, chipo]) await app.get(RedisService).del(`moment:touch:${u.userId}`);
  });

  it('a cooking night: music beside the cooking, one timer both phones count down, and a hug', async () => {
    const m = await start('COOK');
    await join(mosty, m.id);
    const hers = await phone(natasha); const his = await phone(mosty);
    try {
      // Music beside the cooking; the kitchen stays the room.
      const music = (await http().post(`/api/v1/moments/${m.id}/state`).set(auth(natasha)).send({ op: 'ADD', module: 'MUSIC' }).expect(200)).body;
      expect(music).toMatchObject({ primary: 'PRESENCE', scene: 'KITCHEN', secondary: ['MUSIC'] });

      // One timer for the rice, the same instant on both phones.
      const started = (await timer(natasha, m.id, { op: 'START', durationMs: 600_000, label: 'Rice' })).body;
      const onHis = (await his.waitFor((f) => f.type === 'moment.timer' && f.payload.timer.revision === 1)).payload;
      expect(onHis.timer).toEqual(started.timer);
      expect(onHis.timer.endsAt - onHis.serverNow).toBe(600_000);

      // He pauses it; she adds a minute; both phones hold the same timer.
      await timer(mosty, m.id, { op: 'PAUSE' });
      await timer(natasha, m.id, { op: 'ADD', addMs: 60_000 });
      const hersLast = (await hers.waitFor((f) => f.type === 'moment.timer' && f.payload.timer.revision === 3)).payload.timer;
      const hisLast = (await his.waitFor((f) => f.type === 'moment.timer' && f.payload.timer.revision === 3)).payload.timer;
      expect(hersLast).toEqual(hisLast);
      expect(hisLast.status).toBe('PAUSED');
      expect(hisLast.remainingMs).toBeGreaterThan(655_000);

      // A hug, felt on his phone with her name; nothing comes back to hers.
      await touch(natasha, m.id, { kind: 'HUG', to: mosty.userId });
      const felt = (await his.waitFor((f) => f.type === 'moment.touch')).payload;
      expect(felt).toMatchObject({ momentId: m.id, from: natasha.userId, kind: 'HUG', to: mosty.userId });
      expect(felt.fromName).toBeTruthy();
      expect(felt.fromName).not.toBe(natasha.userId);
      await new Promise((r) => setTimeout(r, 150));
      expect(hers.frames.some((f) => f.type === 'moment.touch')).toBe(false);

      // A phone that comes back finds the timer where it is.
      const room = (await http().get(`/api/v1/moments/${m.id}/room`).set(auth(mosty)).expect(200)).body;
      expect(room.timer).toEqual(hisLast);
      expect(room.state.secondary).toEqual(['MUSIC']);
    } finally { hers.socket.close(); his.socket.close(); }
  });

  it('a touch to everyone reaches everyone else; too many is refused; only people here can be touched', async () => {
    const m = await start();
    await join(mosty, m.id); await join(chipo, m.id);
    const his = await phone(mosty); const theirs = await phone(chipo); const hers = await phone(natasha);
    try {
      await touch(natasha, m.id, { kind: 'HEART' });
      await his.waitFor((f) => f.type === 'moment.touch' && f.payload.kind === 'HEART');
      await theirs.waitFor((f) => f.type === 'moment.touch' && f.payload.kind === 'HEART');
      await new Promise((r) => setTimeout(r, 150));
      expect(hers.frames.some((f) => f.type === 'moment.touch')).toBe(false);

      await touch(natasha, m.id, { kind: 'WAVE', to: stranger.userId }, 400);
      await touch(natasha, m.id, { kind: 'WAVE', to: natasha.userId }, 400);
      await touch(natasha, m.id, { kind: 'SLAP' }, 400);
      await touch(stranger, m.id, { kind: 'HEART' }, 404);
      for (let i = 0; i < 9; i++) await touch(natasha, m.id, { kind: 'TAP' });
      await touch(natasha, m.id, { kind: 'TAP' }, 429);
    } finally { his.socket.close(); theirs.socket.close(); hers.socket.close(); }
  });

  it('help me choose: everyone answers, the asker decides, and an answer leaves with its person', async () => {
    const m = await start('CHOOSE');
    await join(mosty, m.id); await join(chipo, m.id);
    const his = await phone(mosty);
    try {
      await choice(natasha, m.id, { op: 'ASK', question: 'Which dress for Sunday?', options: ['Blue', 'Green', 'Chitenge'] });
      await his.waitFor((f) => f.type === 'moment.choice' && f.payload.choice.status === 'OPEN');
      await choice(mosty, m.id, { op: 'PICK', optionId: '3' });
      await choice(chipo, m.id, { op: 'PICK', optionId: '1' });
      let c = (await http().get(`/api/v1/moments/${m.id}/room`).set(auth(mosty)).expect(200)).body.choice;
      expect(c.picks).toEqual({ [mosty.userId]: '3', [chipo.userId]: '1' });

      await http().post(`/api/v1/moments/${m.id}/leave`).set(auth(chipo)).expect(200);
      c = (await http().get(`/api/v1/moments/${m.id}/room`).set(auth(mosty)).expect(200)).body.choice;
      expect(c.picks).toEqual({ [mosty.userId]: '3' });

      await choice(mosty, m.id, { op: 'DECIDE', optionId: '3' }, 400);
      const decided = (await choice(natasha, m.id, { op: 'DECIDE', optionId: '3' })).body.choice;
      expect(decided).toMatchObject({ status: 'DECIDED', decided: '3' });
      await his.waitFor((f) => f.type === 'moment.choice' && f.payload.choice.status === 'DECIDED');

      await choice(natasha, m.id, { op: 'ASK', question: 'One?', options: ['Only'] }, 400);
      await choice(stranger, m.id, { op: 'PICK', optionId: '1' }, 404);
    } finally { his.socket.close(); }
  });

  it('only people in the room can use the timer; nonsense is refused; ending clears it all', async () => {
    const m = await start();
    await timer(mosty, m.id, { op: 'START', durationMs: 60_000 }, 403);
    await timer(stranger, m.id, { op: 'START', durationMs: 60_000 }, 404);
    await timer(natasha, m.id, { op: 'START', durationMs: 10 }, 400);
    await timer(natasha, m.id, { op: 'PAUSE' }, 400);
    await timer(natasha, m.id, { op: 'START', durationMs: 60_000, label: 'Tea' });
    await choice(natasha, m.id, { op: 'ASK', question: 'Tea or coffee?', options: ['Tea', 'Coffee'] });
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(natasha)).expect(200);
    const redis = app.get(RedisService);
    expect(await redis.getJson(`moment:timer:${m.id}`)).toBeNull();
    expect(await redis.getJson(`moment:choice:${m.id}`)).toBeNull();
  });
});
