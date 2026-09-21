/**
 * Phase 0.6 integration tests — Redis, presence, security gates.
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

async function resetDatabase() {
  const client = new Client({ connectionString: DATABASE_URL });
  await client.connect();
  await client.query('DROP SCHEMA public CASCADE');
  await client.query('CREATE SCHEMA public');
  await client.query('GRANT ALL ON SCHEMA public TO viro');
  await client.query('GRANT ALL ON SCHEMA public TO public');
  const dir = join(__dirname, '../../src/database/migrations');
  const files = require('fs')
    .readdirSync(dir)
    .filter((f: string) => f.endsWith('.sql'))
    .sort();
  for (const file of files) {
    await client.query(readFileSync(join(dir, file), 'utf-8'));
  }
  await client.end();
}

async function registerUser(app: INestApplication, phone: string) {
  const otpRes = await request(app.getHttpServer())
    .post('/api/v1/auth/otp/request')
    .send({ phoneE164: phone });
  const verifyRes = await request(app.getHttpServer())
    .post('/api/v1/auth/otp/verify')
    .send({
      challengeId: otpRes.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${phone}`,
      platform: 'ANDROID',
      appVersion: '0.1.0',
    });
  return verifyRes.body;
}

describe('Phase 0.6 Integration', () => {
  let app: INestApplication;
  let pgAvailable = false;
  let redisAvailable = false;

  beforeAll(async () => {
    try {
      const client = new Client({ connectionString: DATABASE_URL });
      await client.connect();
      await client.query('SELECT 1');
      await client.end();
      pgAvailable = true;
    } catch {
      return;
    }

    try {
      const redis = new RedisService();
      redisAvailable = await redis.ping();
      await redis.onModuleDestroy();
    } catch {
      redisAvailable = false;
    }

    if (!pgAvailable || !redisAvailable) return;
    await resetDatabase();
    app = await createTestApp({ manyOtpFixtures: true });
  });

  afterAll(async () => {
    if (app) await app.close();
  });

  const skipIfUnavailable = () => {
    if (!pgAvailable || !redisAvailable) {
      throw new Error('PostgreSQL and Redis are required for this integration suite.');
    }
    return false;
  };

  it('expired access token returns 401; refresh restores access', async () => {
    if (skipIfUnavailable()) return;
    const user = await registerUser(app, '+260971100100');

    await request(app.getHttpServer())
      .get('/api/v1/me')
      .set('Authorization', 'Bearer invalid.token.here')
      .expect(401);

    const refresh = await request(app.getHttpServer())
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: user.refreshToken })
      .expect((res) => expect([200, 201]).toContain(res.status));

    await request(app.getHttpServer())
      .get('/api/v1/me')
      .set('Authorization', `Bearer ${refresh.body.accessToken}`)
      .expect(200);
  });

  it('presence hides blocked user as OFFLINE', async () => {
    if (skipIfUnavailable()) return;
    const alice = await registerUser(app, '+260971100101');
    const bob = await registerUser(app, '+260971100102');

    // Presence defaults to contacts only. Establish the authorized relationship
    // before asserting that blocking removes previously visible presence.
    await request(app.getHttpServer())
      .post('/api/v1/contacts/discover')
      .set('Authorization', `Bearer ${alice.accessToken}`)
      .send({ phonesE164: ['+260971100102'] })
      .expect(201);

    await request(app.getHttpServer())
      .post('/api/v1/presence')
      .set('Authorization', `Bearer ${bob.accessToken}`)
      .send({ state: 'ONLINE' })
      .expect((res) => expect([200, 201]).toContain(res.status));

    const beforeBlock = await request(app.getHttpServer())
      .get(`/api/v1/presence/${bob.userId}`)
      .set('Authorization', `Bearer ${alice.accessToken}`);
    expect(beforeBlock.body.state).toBe('ONLINE');

    await request(app.getHttpServer())
      .post('/api/v1/blocks')
      .set('Authorization', `Bearer ${bob.accessToken}`)
      .send({ blockedUserId: alice.userId })
      .expect((res) => expect([200, 201]).toContain(res.status));

    const afterBlock = await request(app.getHttpServer())
      .get(`/api/v1/presence/${bob.userId}`)
      .set('Authorization', `Bearer ${alice.accessToken}`);
    expect(afterBlock.body.state).toBe('OFFLINE');
    expect(afterBlock.body).not.toHaveProperty('blocked');
  });

  it('suspended user cannot refresh or access protected endpoints', async () => {
    if (skipIfUnavailable()) return;
    const user = await registerUser(app, '+260971100103');

    const client = new Client({ connectionString: DATABASE_URL });
    await client.connect();
    await client.query(`UPDATE users SET status = 'SUSPENDED' WHERE id = $1`, [user.userId]);
    await client.end();

    await request(app.getHttpServer())
      .get('/api/v1/me')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .expect(401);

    await request(app.getHttpServer())
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: user.refreshToken })
      .expect(401);
  });

  it('ephemeral resolve returns unauthorized for unknown peer', async () => {
    if (skipIfUnavailable()) return;
    const alice = await registerUser(app, '+260971100104');
    const resolve = await request(app.getHttpServer())
      .post('/api/v1/discovery/ephemeral/resolve')
      .set('Authorization', `Bearer ${alice.accessToken}`)
      .send({
        ephemeralId: 'vr1_NotRegisteredEphemeralIdxxxx',
        authorizedUserIds: [],
      });
    expect(resolve.body.authorized).toBe(false);
  });

  it('TURN credentials require authentication', async () => {
    if (skipIfUnavailable()) return;
    await request(app.getHttpServer())
      .post('/api/v1/turn/credentials')
      .expect(401);
  });

  it('issues TURN credentials for authenticated device', async () => {
    if (skipIfUnavailable()) return;
    const user = await registerUser(app, '+260971100105');
    const res = await request(app.getHttpServer())
      .post('/api/v1/turn/credentials')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .expect(201);
    expect(res.body.urls.length).toBeGreaterThan(0);
    expect(res.body.username).toContain(user.userId);
    expect(res.body.credential).toBeTruthy();
  });
});
