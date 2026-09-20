/**
 * End-to-end: linking another device (the web companion) — the code, the
 * approval from a signed-in phone, the one-time handover of tokens, and what
 * the linked session may then do.
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

async function sql(text: string, params: unknown[] = []) {
  const client = new Client({ connectionString: DATABASE_URL });
  await client.connect();
  try {
    return (await client.query(text, params)).rows;
  } finally {
    await client.end();
  }
}

jest.setTimeout(30_000);

type User = { userId: string; accessToken: string };

describe('Linking another device', () => {
  let app: INestApplication;
  let available = false;
  let phone: User;
  let other: User;
  const http = () => request(app.getHttpServer());
  const as = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });

  async function register(phoneE164: string, name: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164 });
    const v = await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${phoneE164}`,
      platform: 'ANDROID',
      appVersion: '0.4.0',
    });
    await http().patch('/api/v1/me').set({ Authorization: `Bearer ${v.body.accessToken}` }).send({ displayName: name });
    return v.body;
  }

  /** What the browser does first. */
  const startLink = async (label = 'Chrome on Windows') =>
    (await http().post('/api/v1/devices/link/start').send({ label }).expect(201)).body;

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
    phone = await register('+260978700001', 'Chanda');
    other = await register('+260978700002', 'Someone else');
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  it('links a browser: code, approval, and tokens handed over once', async () => {
    if (skip()) return;
    const link = await startLink();
    expect(link.code).toMatch(/^[A-HJ-NP-Z2-9]{8}$/);
    expect(link.secret).toBeTruthy();

    // Nothing to collect while it waits.
    const waiting = await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: link.secret }).expect(200);
    expect(waiting.body.status).toBe('PENDING');

    const approved = await http().post('/api/v1/devices/link/approve').set(as(phone)).send({ code: link.code }).expect(201);
    expect(approved.body).toMatchObject({ linked: true, label: 'Chrome on Windows' });

    const claimed = await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: link.secret }).expect(200);
    expect(claimed.body.status).toBe('APPROVED');
    expect(claimed.body.accessToken).toBeTruthy();
    expect(claimed.body.userId).toBe(phone.userId);
    expect(claimed.body.displayName).toBe('Chanda');

    // The tokens are gone from the row once collected.
    const again = await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: link.secret }).expect(200);
    expect(again.body.status).toBe('CLAIMED');
    expect(again.body.accessToken).toBeUndefined();
    expect((await sql('SELECT tokens FROM device_link_requests WHERE id = $1', [link.linkId]))[0].tokens).toBeNull();

    // And the browser's session is this person, on the API the app uses.
    const me = await http().get('/api/v1/me').set({ Authorization: `Bearer ${claimed.body.accessToken}` }).expect(200);
    expect(me.body.userId).toBe(phone.userId);
  });

  it('shows the browser in Devices, where it can be cut off', async () => {
    if (skip()) return;
    const link = await startLink('Firefox on Ubuntu');
    await http().post('/api/v1/devices/link/approve').set(as(phone)).send({ code: link.code }).expect(201);
    const tokens = (await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: link.secret }).expect(200)).body;

    const devices = await http().get('/api/v1/devices').set(as(phone)).expect(200);
    const web = devices.body.find((d: any) => d.platform === 'WEB' && d.appVersion === 'Firefox on Ubuntu');
    expect(web).toBeTruthy();

    await http().delete(`/api/v1/devices/${web.id}`).set(as(phone)).expect(200);
    expect((await sql('SELECT revoked_at FROM devices WHERE id = $1', [web.id]))[0].revoked_at).toBeTruthy();
    // The token itself still parses; the device row is what was cut off.
    expect(tokens.accessToken).toBeTruthy();
  });

  it('gives the tokens only to the browser that asked', async () => {
    if (skip()) return;
    const link = await startLink();
    await http().post('/api/v1/devices/link/approve').set(as(phone)).send({ code: link.code }).expect(201);
    // Knowing the link id is not enough.
    await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: 'guessed' }).expect(404);
    await http().get(`/api/v1/devices/link/${link.linkId}`).expect(404);
    // The right secret still works.
    expect((await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: link.secret }).expect(200)).body.status)
      .toBe('APPROVED');
  });

  it('refuses a code that is wrong, used, or expired', async () => {
    if (skip()) return;
    await http().post('/api/v1/devices/link/approve').set(as(phone)).send({ code: 'ZZZZZZZZ' }).expect(404);

    const used = await startLink();
    await http().post('/api/v1/devices/link/approve').set(as(phone)).send({ code: used.code }).expect(201);
    await http().post('/api/v1/devices/link/approve').set(as(other)).send({ code: used.code }).expect(400);

    const stale = await startLink();
    await sql('UPDATE device_link_requests SET expires_at = NOW() - INTERVAL \'1 minute\' WHERE id = $1', [stale.linkId]);
    await http().post('/api/v1/devices/link/approve').set(as(phone)).send({ code: stale.code }).expect(404);
    expect((await http().get(`/api/v1/devices/link/${stale.linkId}`).query({ secret: stale.secret }).expect(200)).body.status)
      .toBe('EXPIRED');
  });

  it('needs someone signed in to approve', async () => {
    if (skip()) return;
    const link = await startLink();
    await http().post('/api/v1/devices/link/approve').send({ code: link.code }).expect(401);
    // Still waiting afterwards.
    expect((await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: link.secret }).expect(200)).body.status)
      .toBe('PENDING');
  });

  it('links to whoever approved, not to whoever asked', async () => {
    if (skip()) return;
    const link = await startLink();
    await http().post('/api/v1/devices/link/approve').set(as(other)).send({ code: link.code }).expect(201);
    const claimed = await http().get(`/api/v1/devices/link/${link.linkId}`).query({ secret: link.secret }).expect(200);
    expect(claimed.body.userId).toBe(other.userId);
    const me = await http().get('/api/v1/me').set({ Authorization: `Bearer ${claimed.body.accessToken}` }).expect(200);
    expect(me.body.userId).toBe(other.userId);
  });

  it('stops someone hammering codes', async () => {
    if (skip()) return;
    let refused = 0;
    for (let i = 0; i < 14; i++) {
      const res = await http().post('/api/v1/devices/link/approve').set(as(other)).send({ code: `AAAA${i}BBB` });
      if (res.status === 429) refused++;
    }
    expect(refused).toBeGreaterThan(0);
  });
});
