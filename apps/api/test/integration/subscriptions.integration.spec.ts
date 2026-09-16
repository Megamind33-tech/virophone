/**
 * Subscriptions stub (E3): list plans + select plan without a payment provider.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { createTestApp } from './test-app';
import { RedisService } from '../../src/redis/redis.service';
import { DATABASE_URL, resetDatabase } from './reset-db';

async function registerUser(app: INestApplication, phone: string, key: string) {
  const otpRes = await request(app.getHttpServer())
    .post('/api/v1/auth/otp/request')
    .send({ phoneE164: phone });
  const verifyRes = await request(app.getHttpServer())
    .post('/api/v1/auth/otp/verify')
    .send({
      challengeId: otpRes.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: key,
      platform: 'ANDROID',
      appVersion: '0.1.0',
    });
  if (![200, 201].includes(verifyRes.status)) {
    throw new Error(`OTP verify failed: ${JSON.stringify(verifyRes.body)}`);
  }
  return verifyRes.body as { userId: string; deviceId: string; accessToken: string };
}

describe('Subscriptions API', () => {
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

  it('lists seeded plans and lets a user select Plus', async () => {
    if (skip()) return;
    const user = await registerUser(app, '+260977000301', 'subkey');

    const plans = await request(app.getHttpServer()).get('/api/v1/plans');
    expect(plans.status).toBe(200);
    expect(plans.body.length).toBeGreaterThanOrEqual(2);
    const plus = plans.body.find((p: { name: string }) => p.name === 'Plus');
    expect(plus).toBeTruthy();

    const mineBefore = await request(app.getHttpServer())
      .get('/api/v1/me/subscription')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(mineBefore.status).toBe(200);
    expect(mineBefore.body.planName).toBe('Free');

    const selected = await request(app.getHttpServer())
      .post('/api/v1/me/subscription')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ planId: plus.id });
    expect([200, 201]).toContain(selected.status);
    expect(selected.body.planName).toBe('Plus');
    expect(selected.body.isDefault).toBe(false);
  });
});
