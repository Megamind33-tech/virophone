jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

/**
 * The Moment room engine, stage A: one room whose primary experience changes
 * while the people in it stay.
 *
 * Two authenticated accounts, each on a real socket to the existing signaling
 * gateway. What one of them does to the room, the other must see — and a
 * phone that reconnects must land in whatever the room has become, never in
 * what it was when it left.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import * as WebSocket from 'ws';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { SignalingGateway } from '../../src/signaling/signaling.gateway';
import { RedisService } from '../../src/redis/redis.service';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string };

describe('Moment room engine: one room that changes around people', () => {
  let app: INestApplication;
  let db: Client;
  let natasha: User; let mosty: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });

  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({ challengeId: otp.body.challengeId,
      code: '123456', devicePublicKey: `engine-${n}`, platform: 'ANDROID', appVersion: 'engine-test' }).expect(201)).body;
  }
  const start = async (intent: string, u = natasha) =>
    (await http().post('/api/v1/moments').set(auth(u))
      .send({ type: 'FREE', intent, visibility: 'CONNECTIONS', durationMinutes: 60 }).expect(201)).body;
  const change = (u: User, id: string, body: Record<string, unknown>, status = 200) =>
    http().post(`/api/v1/moments/${id}/state`).set(auth(u)).send(body).expect(status);
  const roomOf = async (u: User, id: string) =>
    (await http().get(`/api/v1/moments/${id}/room`).set(auth(u)).expect(200)).body;

  /** A phone on the real socket, collecting every frame it is sent. */
  async function phone(u: User) {
    const port = app.getHttpServer().address().port;
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${u.accessToken}`);
    const frames: any[] = [];
    socket.on('message', (raw) => frames.push(JSON.parse(raw.toString())));
    await new Promise<void>((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
    await new Promise((r) => setTimeout(r, 100));
    const stateFrames = (momentId: string) =>
      frames.filter((f) => f.type === 'moment.state' && f.payload.momentId === momentId).map((f) => f.payload.state);
    const waitForRevision = async (momentId: string, revision: number) => {
      for (let i = 0; i < 200; i++) {
        const found = stateFrames(momentId).find((s: any) => s.revision === revision);
        if (found) return found;
        await new Promise((r) => setTimeout(r, 20));
      }
      throw new Error(`No moment.state frame at revision ${revision}`);
    };
    return { socket, frames, stateFrames, waitForRevision };
  }

  beforeAll(async () => {
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    natasha = await register('+260978730001'); mosty = await register('+260978730002'); stranger = await register('+260978730003');
    await db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`,
      [natasha.userId, mosty.userId]);
  }, 120000);
  afterAll(async () => { if (app) await app.close(); if (db) await db.end(); });
  beforeEach(async () => { await db.query('DELETE FROM moments'); await db.query('DELETE FROM blocks'); });

  it('opens "Cook with me" as live presence in a kitchen, for everyone in it', async () => {
    const m = await start('COOK');
    expect(m.intent).toBe('COOK');
    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(mosty)).expect(200);
    for (const u of [natasha, mosty]) {
      const room = await roomOf(u, m.id);
      expect(room.state).toMatchObject({ primary: 'PRESENCE', scene: 'KITCHEN', intent: 'COOK', revision: 1 });
    }
  });

  it('keeps one room through cook, music, a film and quiet — both phones in step', async () => {
    const m = await start('COOK');
    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(mosty)).expect(200);
    const a = await phone(natasha);
    const b = await phone(mosty);
    const disconnect = jest.spyOn(app.get(SignalingGateway), 'handleDisconnect');
    try {
      // Natasha adds music: video presence stays the room, the player sits beside it.
      await change(natasha, m.id, { op: 'ADD', module: 'MUSIC' });
      expect(await b.waitForRevision(m.id, 2)).toMatchObject({ primary: 'PRESENCE', secondary: ['MUSIC'] });

      // Mosty says "Watch something": the room becomes the player, and nobody leaves.
      await change(mosty, m.id, { op: 'TRANSFORM', intent: 'WATCH' });
      const onA = await a.waitForRevision(m.id, 3);
      expect(onA).toMatchObject({ primary: 'VIDEO', scene: 'CINEMA', updatedBy: mosty.userId });

      // "Just stay": quiet presence, the tools gone.
      await change(natasha, m.id, { op: 'TRANSFORM', intent: 'STAY' });
      expect(await b.waitForRevision(m.id, 4)).toMatchObject({ primary: 'QUIET', scene: 'QUIET' });

      // Same Moment, same people, all the way through.
      const room = await roomOf(mosty, m.id);
      expect(room.moment.id).toBe(m.id);
      expect(room.participants.map((p: any) => p.userId).sort()).toEqual([natasha.userId, mosty.userId].sort());
      expect(room.state.revision).toBe(4);
    } finally {
      a.socket.close(); b.socket.close();
      for (let i = 0; i < 100 && disconnect.mock.results.length < 2; i++) await new Promise((r) => setTimeout(r, 20));
      for (const result of disconnect.mock.results) await result.value;
      disconnect.mockRestore();
    }
  });

  it('puts a phone that was away into what the room became, not what it left', async () => {
    const m = await start('COOK');
    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(mosty)).expect(200);
    // Mosty's phone is gone; the room moves on without it.
    await change(natasha, m.id, { op: 'TRANSFORM', intent: 'WATCH' });
    await change(natasha, m.id, { op: 'ADD', module: 'TIMER' });
    // Back: the room read is the truth, and it is the film.
    const room = await roomOf(mosty, m.id);
    expect(room.state).toMatchObject({ primary: 'VIDEO', secondary: ['TIMER'], revision: 3 });
    // Rejoining is idempotent and does not reset the room either.
    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(mosty)).expect(200);
    expect((await roomOf(mosty, m.id)).state.revision).toBe(3);
  });

  it('rebuilds the shape of a room if Redis loses it, rather than failing to open', async () => {
    const m = await start('LISTEN');
    await app.get(RedisService).del(`moment:room:${m.id}`);
    const room = await roomOf(natasha, m.id);
    expect(room.state).toMatchObject({ primary: 'MUSIC', scene: 'LISTENING' });
  });

  it('opens a Moment made before intents existed into a room derived from its type', async () => {
    const m = (await http().post('/api/v1/moments').set(auth(natasha))
      .send({ type: 'WATCHING', visibility: 'CONNECTIONS', durationMinutes: 30 }).expect(201)).body;
    expect(m.intent).toBe('WATCH');
    expect((await roomOf(natasha, m.id)).state.primary).toBe('VIDEO');
  });

  it('lets only people in the room change it, and refuses changes that make no sense', async () => {
    const m = await start('COOK');
    // Mosty can see the Moment but is not in the room yet.
    await change(mosty, m.id, { op: 'TRANSFORM', intent: 'WATCH' }, 403);
    // A stranger cannot even learn it exists.
    await change(stranger, m.id, { op: 'TRANSFORM', intent: 'WATCH' }, 404);
    await change(natasha, m.id, { op: 'ADD', module: 'PRESENCE' }, 400);
    await change(natasha, m.id, { op: 'TRANSFORM', intent: 'DANCE' }, 400);
    await change(natasha, m.id, { op: 'TRANSFORM' }, 400);
    expect((await roomOf(natasha, m.id)).state.revision).toBe(1);
  });

  it('does not lose either change when two people change the room at once', async () => {
    const m = await start('COOK');
    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(mosty)).expect(200);
    await Promise.all([
      change(natasha, m.id, { op: 'ADD', module: 'MUSIC' }),
      change(mosty, m.id, { op: 'ADD', module: 'TIMER' }),
    ]);
    const state = (await roomOf(natasha, m.id)).state;
    expect(state.revision).toBe(3);
    expect([...state.secondary].sort()).toEqual(['MUSIC', 'TIMER']);
  });

  it('keeps a blocked person out of the room and out of its changes', async () => {
    const m = await start('COOK');
    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(mosty)).expect(200);
    await http().post('/api/v1/blocks').set(auth(natasha)).send({ blockedUserId: mosty.userId }).expect(201);
    await change(mosty, m.id, { op: 'TRANSFORM', intent: 'WATCH' }, 404);
    await http().get(`/api/v1/moments/${m.id}/room`).set(auth(mosty)).expect(404);
  });

  it('closes the room shape with the Moment, so nothing can rebuild it', async () => {
    const m = await start('COOK');
    await change(natasha, m.id, { op: 'TRANSFORM', intent: 'STAY' });
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(natasha)).expect(200);
    expect(await app.get(RedisService).getJson(`moment:room:${m.id}`)).toBeNull();
    await change(natasha, m.id, { op: 'TRANSFORM', intent: 'WATCH' }, 404);
  });
});
