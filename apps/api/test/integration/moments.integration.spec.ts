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
import * as WebSocket from 'ws';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { MomentsClock } from '../../src/moments/moments.module';
import { RealtimeRegistry } from '../../src/realtime/realtime.registry';
import { SignalingGateway } from '../../src/signaling/signaling.gateway';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string };
describe('Viro Now: authenticated visibility and lifecycle', () => {
  let app: INestApplication;
  let db: Client;
  let a: User; let b: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });
  const body = { type: 'FREE', visibility: 'CONNECTIONS', durationMinutes: 15 };
  const create = async (u = a, overrides = {}) =>
    (await http().post('/api/v1/moments').set(auth(u)).send({ ...body, ...overrides }).expect(201)).body;
  const now = async (u: User) => (await http().get('/api/v1/moments/now').set(auth(u)).expect(200)).body.moments;
  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({ challengeId: otp.body.challengeId,
      code: '123456', devicePublicKey: `moments-${n}`, platform: 'ANDROID', appVersion: 'moments-test' }).expect(201)).body;
  }
  beforeAll(async () => {
    // Fail if the database is unavailable. Never report an unexecuted test as green.
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp(); await app.listen(0, '127.0.0.1');
    a = await register('+260978710001'); b = await register('+260978710002'); stranger = await register('+260978710003');
    await db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`, [a.userId, b.userId]);
  }, 120000);
  afterAll(async () => { if (app) await app.close(); if (db) await db.end(); });
  beforeEach(async () => {
    await db.query('DELETE FROM moments'); await db.query('DELETE FROM blocks'); await db.query('DELETE FROM contact_matches');
    await db.query(`UPDATE viro_connections SET status = 'ACCEPTED'`);
    await db.query(`UPDATE profiles SET photo_visibility = 'EVERYONE', avatar_url = NULL`);
  });

  it('requires authentication', async () => { await http().get('/api/v1/moments/now').expect(401); });
  it('persists a Moment and returns it to the creator and accepted connection only', async () => {
    const m = await create();
    expect((await now(a))[0].id).toBe(m.id); expect((await now(b))[0].id).toBe(m.id);
    expect(await now(stranger)).toEqual([]);
    expect((await db.query('SELECT status FROM moments WHERE id=$1', [m.id])).rows[0].status).toBe('ACTIVE');
    await http().get(`/api/v1/moments/${m.id}`).set(auth(stranger)).expect(404);
  });
  it('does not expose inactive, nonexistent or blocked identifiers differently', async () => {
    const m = await create(); await http().post('/api/v1/blocks').set(auth(a)).send({ blockedUserId: b.userId }).expect(201);
    const blocked = await http().get(`/api/v1/moments/${m.id}`).set(auth(b)).expect(404);
    const missing = await http().get('/api/v1/moments/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa').set(auth(b)).expect(404);
    expect(blocked.body.message).toEqual(missing.body.message);
    expect(await now(b)).toEqual([]);
  });
  it('honors blocks in the reverse direction too', async () => {
    const m = await create(); await http().post('/api/v1/blocks').set(auth(b)).send({ blockedUserId: a.userId }).expect(201);
    expect(await now(b)).toEqual([]); await http().get(`/api/v1/moments/${m.id}`).set(auth(b)).expect(404);
  });
  it('filters a block already present when creating', async () => {
    await http().post('/api/v1/blocks').set(auth(a)).send({ blockedUserId: b.userId }).expect(201);
    await create(); expect(await now(b)).toEqual([]);
  });
  it('rejects public visibility and invalid input', async () => {
    for (const invalid of [{ visibility: 'EVERYONE' }, { type: 'VIDEO' }, { durationMinutes: 0 },
      { durationMinutes: 121 }, { durationMinutes: 1.5 }, { type: 'CUSTOM', text: '  ' }, { text: 'x'.repeat(61) }]) {
      await http().post('/api/v1/moments').set(auth(a)).send({ ...body, ...invalid }).expect(400);
    }
  });
  it('allows all seven types and a sixty-character custom description', async () => {
    for (const type of ['FREE','BREAK','LISTENING','WATCHING','GAMING','WORKING','CUSTOM']) {
      const m = await create(a, { type, text: 'x'.repeat(60) });
      expect(m.type).toBe(type); expect(m.allowVoice).toBe(type === 'FREE');
      await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    }
  });
  it('enforces one active Moment even for concurrent create requests', async () => {
    const results = await Promise.all([1,2].map(() => http().post('/api/v1/moments').set(auth(a)).send(body)));
    expect(results.map(r => r.status).sort()).toEqual([201,409]); expect(await now(a)).toHaveLength(1);
  });
  it('only lets the owner end or extend', async () => {
    const m = await create();
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(b)).expect(404);
    await http().post(`/api/v1/moments/${m.id}/extend`).set(auth(b)).send({ minutes: 15 }).expect(404);
  });
  it('extends and caps lifetime at two hours', async () => {
    const m = await create(a, { durationMinutes: 120 });
    const result = await http().post(`/api/v1/moments/${m.id}/extend`).set(auth(a)).send({ minutes: 60 }).expect(201);
    expect(Date.parse(result.body.expiresAt) - Date.parse(result.body.createdAt)).toBe(7200000);
  });
  it('ends immediately and allows another Moment', async () => {
    const m = await create(); await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    expect(await now(b)).toEqual([]); await http().get(`/api/v1/moments/${m.id}`).set(auth(a)).expect(404);
    await create();
  });
  it('refuses expired reads and extension before the expiry worker runs', async () => {
    const m = await create();
    await db.query(`UPDATE moments SET created_at=now()-interval '2 minutes',expires_at=now()-interval '1 minute' WHERE id=$1`, [m.id]);
    expect(await now(b)).toEqual([]);
    await http().get(`/api/v1/moments/${m.id}`).set(auth(a)).expect(404);
    await http().post(`/api/v1/moments/${m.id}/extend`).set(auth(a)).send({ minutes: 15 }).expect(404);
    await app.get(MomentsClock).tick();
    expect((await db.query('SELECT status FROM moments WHERE id=$1', [m.id])).rows[0].status).toBe('EXPIRED');
    await app.get(MomentsClock).tick(); expect(await now(a)).toEqual([]); await create();
  });
  it('uses host-owned contacts, not strangers who saved the host number', async () => {
    await db.query(`INSERT INTO contact_matches(user_id,matched_user_id,phone_hash,expires_at) VALUES ($1,$2,'hash',now()+interval '1 day')`, [b.userId,a.userId]);
    await create(a, { visibility: 'CONTACTS' }); expect(await now(b)).toEqual([]);
    await db.query(`INSERT INTO contact_matches(user_id,matched_user_id,phone_hash,expires_at) VALUES ($1,$2,'hash',now()+interval '1 day')`, [a.userId,b.userId]);
    expect(await now(b)).toHaveLength(1);
    await db.query(`UPDATE contact_matches SET expires_at=now()-interval '1 day'`); expect(await now(b)).toEqual([]);
  });
  it('does not resurrect an expired Moment after the API restarts', async () => {
    const m = await create();
    await db.query(`UPDATE moments SET created_at=now()-interval '2 minutes',expires_at=now()-interval '1 minute' WHERE id=$1`, [m.id]);
    await app.close();
    app = await createTestApp();
    await app.listen(0, '127.0.0.1');
    expect(await now(a)).toEqual([]);
    expect(await now(b)).toEqual([]);
    await http().get(`/api/v1/moments/${m.id}`).set(auth(b)).expect(404);
    await app.get(MomentsClock).tick();
    expect((await db.query('SELECT status FROM moments WHERE id=$1', [m.id])).rows[0].status).toBe('EXPIRED');
    await create();
  });
  it('does not treat pending or revoked connections as an audience', async () => {
    await create();
    for (const status of ['PENDING','REVOKED']) {
      await db.query('UPDATE viro_connections SET status=$1', [status]); expect(await now(b)).toEqual([]);
    }
  });
  it('honors avatar privacy', async () => {
    await db.query(`UPDATE profiles SET avatar_url='/api/v1/media/avatars/test.jpg', photo_visibility='NOBODY' WHERE user_id=$1`, [a.userId]);
    await create(); expect((await now(b))[0].avatarUrl).toBeNull(); expect((await now(a))[0].avatarUrl).toBeTruthy();
  });
  it('never sends lifecycle frames to a stranger or a blocked connection', async () => {
    const spy = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    await create(); expect(spy.mock.calls.map(c=>c[0])).not.toContain(stranger.userId);
    await db.query('DELETE FROM moments'); await http().post('/api/v1/blocks').set(auth(a)).send({ blockedUserId: b.userId });
    spy.mockClear(); await create(); expect(spy.mock.calls.map(c=>c[0])).not.toContain(b.userId); spy.mockRestore();
  });
  it('delivers create, update, end and automatic expiration through the existing websocket', async () => {
    const disconnect = jest.spyOn(app.get(SignalingGateway), 'handleDisconnect');
    const port = app.getHttpServer().address().port;
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${b.accessToken}`);
    const frames: any[] = []; socket.on('message', raw => frames.push(JSON.parse(raw.toString())));
    const waitFor = async (type: string) => {
      for (let i=0;i<100;i++) { if (frames.some(f=>f.type===type)) return; await new Promise(r=>setTimeout(r,20)); }
      throw new Error(`Missing realtime frame ${type}`);
    };
    try {
      await new Promise<void>((resolve,reject) => { socket.once('open',resolve); socket.once('error',reject); });
      await new Promise(r=>setTimeout(r,100));
      const m = await create(); await waitFor('moment.created');
      await http().post(`/api/v1/moments/${m.id}/extend`).set(auth(a)).send({ minutes: 15 }).expect(201); await waitFor('moment.updated');
      await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200); await waitFor('moment.ended');
      const expiring = await create(); await db.query(`UPDATE moments SET created_at=now()-interval '2 minutes', expires_at=now()-interval '1 minute' WHERE id=$1`, [expiring.id]);
      await app.get(MomentsClock).tick(); await waitFor('moment.expired');
      expect(frames.filter(f=>f.type?.startsWith('moment.')).every(f=>Object.keys(f.payload).length===0)).toBe(true);
    } finally {
      socket.close();
      for (let i=0; i<100 && !disconnect.mock.results.length; i++) await new Promise(r=>setTimeout(r,20));
      for (const result of disconnect.mock.results) await result.value;
      disconnect.mockRestore();
    }
  });
});
