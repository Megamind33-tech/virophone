import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { createTestApp } from './test-app';
import { Client } from 'pg';
import { readFileSync } from 'fs';
import { join } from 'path';
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

describe('Redis Integration', () => {
  let app: INestApplication;
  let redisAvailable = false;

  beforeAll(async () => {
    try {
      const redis = new RedisService();
      redisAvailable = await redis.ping();
      await redis.onModuleDestroy();
    } catch {
      redisAvailable = false;
    }
    if (!redisAvailable) throw new Error('Redis is required for this integration suite.');

    await resetDatabase();
    app = await createTestApp();
    // Same as the other DB-resetting suites: the schema keeps growing, and the
    // default 5 s hook timeout turns a slow DROP SCHEMA into a false failure.
  }, 120000);

  afterAll(async () => {
    if (app) await app.close();
  });

  it('health ready includes redis', async () => {
    if (!redisAvailable) return;
    const res = await request(app.getHttpServer()).get('/health/ready');
    expect(res.body.redis).toBe('connected');
  });

  it('registers and resolves ephemeral ID', async () => {
    if (!redisAvailable) return;
    // Register two users
    const otp1 = await request(app.getHttpServer()).post('/api/v1/auth/otp/request').send({ phoneE164: '+260971100001' });
    const v1 = await request(app.getHttpServer()).post('/api/v1/auth/otp/verify').send({
      challengeId: otp1.body.challengeId, code: '123456', devicePublicKey: 'k1', platform: 'ANDROID', appVersion: '0.1',
    });
    const otp2 = await request(app.getHttpServer()).post('/api/v1/auth/otp/request').send({ phoneE164: '+260971100002' });
    const v2 = await request(app.getHttpServer()).post('/api/v1/auth/otp/verify').send({
      challengeId: otp2.body.challengeId, code: '123456', devicePublicKey: 'k2', platform: 'ANDROID', appVersion: '0.1',
    });

    const eid = 'vr1_Z9BqQ9VxTestEphemeralId128bitsxxxxxxxx';
    await request(app.getHttpServer())
      .post('/api/v1/discovery/ephemeral')
      .set('Authorization', `Bearer ${v2.body.accessToken}`)
      .send({ ephemeralId: eid })
      .expect((res) => expect([200, 201]).toContain(res.status));

    const resolve = await request(app.getHttpServer())
      .post('/api/v1/discovery/ephemeral/resolve')
      .set('Authorization', `Bearer ${v1.body.accessToken}`)
      .send({ ephemeralId: eid, authorizedUserIds: [v2.body.userId] });

    expect(resolve.body.authorized).toBe(true);
    expect(resolve.body.userId).toBe(v2.body.userId);
  });
});
