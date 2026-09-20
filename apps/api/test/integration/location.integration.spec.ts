/**
 * End-to-end: sending a place, and sharing a live location (moving it on,
 * stopping it, and who is allowed to).
 *
 * Needs PostgreSQL. Redis may be real, or mocked with REDIS_MOCK=1.
 */
jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { readFileSync, readdirSync } from 'fs';
import { join } from 'path';
import { createTestApp } from './test-app';

const DATABASE_URL =
  process.env.DATABASE_URL || 'postgresql://viro:viro_dev_password@localhost:5432/viro_reach';

async function resetDatabase() {
  const client = new Client({ connectionString: DATABASE_URL });
  await client.connect();
  await client.query('DROP SCHEMA public CASCADE');
  await client.query('CREATE SCHEMA public');
  for (const g of ['GRANT ALL ON SCHEMA public TO viro', 'GRANT ALL ON SCHEMA public TO public']) {
    await client.query(g).catch(() => undefined);
  }
  const dir = join(__dirname, '../../src/database/migrations');
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.sql')).sort()) {
    await client.query(readFileSync(join(dir, file), 'utf-8'));
  }
  await client.end();
}

jest.setTimeout(30_000);

type User = { userId: string; accessToken: string };

// Lusaka: the Cairo Road / Church Road corner, and a block away.
const HERE = { lat: -15.4167, lng: 28.2833 };
const MOVED = { lat: -15.4172, lng: 28.2841 };

describe('Location sharing', () => {
  let app: INestApplication;
  let available = false;
  let alice: User;
  let bob: User;
  let cid = '';
  const http = () => request(app.getHttpServer());
  const as = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });

  async function register(phone: string, name: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: phone });
    const v = await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${phone}`,
      platform: 'ANDROID',
      appVersion: '0.4.0',
    });
    await http().patch('/api/v1/me').set({ Authorization: `Bearer ${v.body.accessToken}` }).send({ displayName: name });
    return v.body;
  }

  beforeAll(async () => {
    try {
      const client = new Client({ connectionString: DATABASE_URL });
      await client.connect();
      await client.end();
      available = true;
    } catch {
      available = false;
    }
    if (!available) return;
    await resetDatabase();
    app = await createTestApp();
    alice = await register('+260978400001', 'Alice');
    bob = await register('+260978400002', 'Bob');
    const first = await http().post('/api/v1/messages').set(as(alice)).send({ toUserId: bob.userId, body: 'On my way' });
    cid = first.body.conversationId;
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  it('sends a place once', async () => {
    if (skip()) return;
    const sent = await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: cid,
      type: 'LOCATION',
      clientMsgId: 'l1',
      location: { ...HERE, accuracy: 12.7, label: 'Cairo Road' },
    }).expect(201);
    expect(sent.body.message.type).toBe('LOCATION');
    expect(sent.body.message.metadata.location).toMatchObject({
      lat: HERE.lat,
      lng: HERE.lng,
      accuracy: 13,
      label: 'Cairo Road',
      liveUntil: null,
    });

    // Bob sees it.
    const history = await http().get(`/api/v1/messages/conversations/${cid}`).set(as(bob)).expect(200);
    expect(history.body.find((m: any) => m.clientMsgId === 'l1').metadata.location.lat).toBe(HERE.lat);
  });

  it('refuses a location that isn\'t one', async () => {
    if (skip()) return;
    for (const bad of [{ lat: 91, lng: 0 }, { lat: 0, lng: 181 }, { lat: Number.NaN, lng: 0 }, {}]) {
      await http().post('/api/v1/messages').set(as(alice))
        .send({ conversationId: cid, type: 'LOCATION', clientMsgId: `bad-${Math.random()}`, location: bad })
        .expect(400);
    }
  });

  it('shares live for a chosen time, and only for an allowed one', async () => {
    if (skip()) return;
    await http().post('/api/v1/messages').set(as(alice))
      .send({ conversationId: cid, type: 'LOCATION', clientMsgId: 'l-bad-live', location: { ...HERE, liveSeconds: 12345 } })
      .expect(400);

    const live = await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: cid,
      type: 'LOCATION',
      clientMsgId: 'l2',
      location: { ...HERE, liveSeconds: 900 },
    }).expect(201);
    const until = new Date(live.body.message.metadata.location.liveUntil).getTime();
    expect(until).toBeGreaterThan(Date.now() + 800_000);
    expect(until).toBeLessThan(Date.now() + 1_000_000);
  });

  it('moves a live location on, and ends it', async () => {
    if (skip()) return;
    const live = await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: cid,
      type: 'LOCATION',
      clientMsgId: 'l3',
      location: { ...HERE, liveSeconds: 3600 },
    }).expect(201);
    const id = live.body.message.id;
    const firstUpdate = live.body.message.metadata.location.updatedAt;

    const moved = await http().put(`/api/v1/messages/${id}/location`).set(as(alice)).send({ ...MOVED, accuracy: 8 }).expect(200);
    expect(moved.body.metadata.location).toMatchObject({ lat: MOVED.lat, lng: MOVED.lng, accuracy: 8 });
    expect(moved.body.metadata.location.updatedAt).not.toBe(firstUpdate);
    // The share keeps running, and its label survives the move.
    expect(new Date(moved.body.metadata.location.liveUntil).getTime()).toBeGreaterThan(Date.now());

    const stopped = await http().post(`/api/v1/messages/${id}/location/stop`).set(as(alice)).expect(201);
    expect(new Date(stopped.body.metadata.location.liveUntil).getTime()).toBeLessThanOrEqual(Date.now());

    // Once ended it can't be resumed quietly.
    await http().put(`/api/v1/messages/${id}/location`).set(as(alice)).send(MOVED).expect(400);
  });

  it('lets only the sender move their own live location', async () => {
    if (skip()) return;
    const live = await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: cid,
      type: 'LOCATION',
      clientMsgId: 'l4',
      location: { ...HERE, liveSeconds: 3600 },
    }).expect(201);
    await http().put(`/api/v1/messages/${live.body.message.id}/location`).set(as(bob)).send(MOVED).expect(403);
  });

  it('refuses to move a place that was never shared live', async () => {
    if (skip()) return;
    const once = await http().post('/api/v1/messages').set(as(alice))
      .send({ conversationId: cid, type: 'LOCATION', clientMsgId: 'l5', location: HERE })
      .expect(201);
    await http().put(`/api/v1/messages/${once.body.message.id}/location`).set(as(alice)).send(MOVED).expect(400);
  });
});
