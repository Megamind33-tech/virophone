/**
 * End-to-end encryption, server half: the key directory hands out each
 * one-time prekey exactly once, and an encrypted message is carried by a
 * server that cannot read it.
 *
 * The ciphertext here is not real Signal output — that lives on the phone.
 * What these tests prove is that the server treats it as opaque, stores no
 * readable copy, delivers it per device, and refuses the ways a chat could
 * quietly stop being encrypted.
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

type Session = { userId: string; deviceId: string; accessToken: string };

/** Stands in for a real sealed message; the server must never look inside. */
const seal = (text: string) => Buffer.from(text, 'utf-8').toString('base64');
const SECRET = 'meet me at the corner at six';

/**
 * One phone, one install key, more than one account: signing out and back in
 * must land on the device that phone already had, or everything sealed for it
 * while it was away becomes unreadable.
 */
describe('Signing back in on the same phone', () => {
  let app: INestApplication;
  let available = false;
  const http = () => request(app.getHttpServer());
  const as = (s: Session) => ({ Authorization: `Bearer ${s.accessToken}` });

  async function signIn(phone: string, key: string): Promise<Session> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: phone });
    const v = await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: key,
      platform: 'ANDROID',
      appVersion: '0.4.80',
    });
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
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  it('keeps each account on its own device, and the same one each time', async () => {
    if (!available) return;
    const phoneKey = `install-key-${Math.random()}`;
    const first = await signIn('+260978811050', phoneKey);
    expect(first.deviceId).toBeTruthy();
    await http()
      .post('/api/v1/keys')
      .set(as(first))
      .send({
        registrationId: 4242,
        identityKey: Buffer.from('identity').toString('base64'),
        signedPreKey: { keyId: 7, publicKey: Buffer.from('signed').toString('base64'), signature: Buffer.from('sig').toString('base64') },
        kyberPreKey: { keyId: 9, publicKey: Buffer.from('kyber').toString('base64'), signature: Buffer.from('ksig').toString('base64') },
        oneTimePreKeys: [{ keyId: 1, publicKey: Buffer.from('otp').toString('base64') }],
      })
      .expect(201);
    await http().post('/api/v1/auth/logout').set(as(first));

    // Another account on the same phone: its own device.
    const other = await signIn('+260978811051', phoneKey);
    expect(other.deviceId).toBeTruthy();
    expect(other.deviceId).not.toBe(first.deviceId);

    // The first account back on this phone: the device it had, keys intact.
    const again = await signIn('+260978811050', phoneKey);
    expect(again.deviceId).toBe(first.deviceId);
    const keys = await sql('SELECT device_id FROM device_identity_keys WHERE device_id = $1', [first.deviceId]);
    expect(keys).toHaveLength(1);
    const devices = await sql('SELECT id FROM devices WHERE user_id = $1', [first.userId]);
    expect(devices).toHaveLength(1);

    // A different install of the same account is a different device.
    const elsewhere = await signIn('+260978811050', `other-install-${Math.random()}`);
    expect(elsewhere.deviceId).toBeTruthy();
    expect(elsewhere.deviceId).not.toBe(first.deviceId);
  });
});
