/**
 * Email-based OTP authentication end-to-end (cheaper alternative to SMS):
 * request → verify → session → /me, plus returning-user and validation paths.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { readFileSync } from 'fs';
import { join } from 'path';
import { createTestApp } from './test-app';
import { RedisService } from '../../src/redis/redis.service';

const DATABASE_URL =
  process.env.DATABASE_URL ||
  'postgresql://viro:viro_dev_password@localhost:5432/viro_reach';

const MIGRATIONS = [
  '001_initial_schema.sql',
  '002_offline_trust.sql',
  '003_push.sql',
  '004_messaging.sql',
  '005_email_identity.sql',
];

async function resetDatabase() {
  const client = new Client({ connectionString: DATABASE_URL });
  await client.connect();
  await client.query('DROP SCHEMA public CASCADE');
  await client.query('CREATE SCHEMA public');
  await client.query('GRANT ALL ON SCHEMA public TO viro');
  await client.query('GRANT ALL ON SCHEMA public TO public');
  for (const file of MIGRATIONS) {
    await client.query(
      readFileSync(join(__dirname, '../../src/database/migrations', file), 'utf-8'),
    );
  }
  await client.end();
}

async function emailLogin(app: INestApplication, email: string) {
  const req = await request(app.getHttpServer())
    .post('/api/v1/auth/email/otp/request')
    .send({ email });
  expect([200, 201]).toContain(req.status);
  const verify = await request(app.getHttpServer())
    .post('/api/v1/auth/email/otp/verify')
    .send({
      challengeId: req.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${email}`,
      platform: 'ANDROID',
      appVersion: '0.1.0',
    });
  return verify;
}

describe('Email OTP authentication', () => {
  let app: INestApplication;
  let available = false;

  beforeAll(async () => {
    try {
      const c = new Client({ connectionString: DATABASE_URL });
      await c.connect();
      await c.query('SELECT 1');
      await c.end();
      const redis = new RedisService();
      available = await redis.ping();
      await redis.onModuleDestroy();
    } catch {
      available = false;
    }
    if (!available) return;
    await resetDatabase();
    app = await createTestApp();
  });

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => !available;

  it('registers and logs in a new user via email', async () => {
    if (skip()) return;
    const verify = await emailLogin(app, 'alice@example.com');
    expect(verify.status).toBe(201);
    expect(verify.body.accessToken).toBeTruthy();
    expect(verify.body.userId).toBeTruthy();
    expect(verify.body.isNewUser).toBe(true);

    const me = await request(app.getHttpServer())
      .get('/api/v1/me')
      .set('Authorization', `Bearer ${verify.body.accessToken}`);
    expect(me.status).toBe(200);
  });

  it('returns the same user on a second email login', async () => {
    if (skip()) return;
    const first = await emailLogin(app, 'bob@example.com');
    const second = await emailLogin(app, 'BOB@example.com'); // case-insensitive
    expect(first.body.userId).toBe(second.body.userId);
    expect(second.body.isNewUser).toBe(false);
  });

  it('rejects an invalid email address', async () => {
    if (skip()) return;
    const res = await request(app.getHttpServer())
      .post('/api/v1/auth/email/otp/request')
      .send({ email: 'not-an-email' });
    expect(res.status).toBe(400);
  });

  it('rejects a wrong code', async () => {
    if (skip()) return;
    const req = await request(app.getHttpServer())
      .post('/api/v1/auth/email/otp/request')
      .send({ email: 'carol@example.com' });
    const verify = await request(app.getHttpServer())
      .post('/api/v1/auth/email/otp/verify')
      .send({
        challengeId: req.body.challengeId,
        code: '000000',
        devicePublicKey: 'k',
        platform: 'ANDROID',
        appVersion: '0.1.0',
      });
    expect(verify.status).toBe(400);
  });
});
