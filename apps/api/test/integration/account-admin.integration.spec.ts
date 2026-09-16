/**
 * Account deletion / GDPR export (E2) and admin moderation APIs (E4).
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
  return verifyRes.body as { userId: string; deviceId: string; accessToken: string };
}

describe('Account export/delete and admin APIs', () => {
  let app: INestApplication;
  let available = false;
  const previousAdminKey = process.env.ADMIN_API_KEY;

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
    process.env.ADMIN_API_KEY = 'test-admin-key';
    await resetDatabase();
    app = await createTestApp();
  });

  afterAll(async () => {
    if (previousAdminKey === undefined) delete process.env.ADMIN_API_KEY;
    else process.env.ADMIN_API_KEY = previousAdminKey;
    if (app) await app.close();
  });

  const skip = () => !available;

  it('exports and then deletes the authenticated account', async () => {
    if (skip()) return;
    const user = await registerUser(app, '+260977000101', 'delkey');
    const exported = await request(app.getHttpServer())
      .get('/api/v1/me/export')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(exported.status).toBe(200);
    expect(exported.body.userId).toBe(user.userId);
    expect(exported.body.profile.phoneE164).toBe('+260977000101');

    const deleted = await request(app.getHttpServer())
      .delete('/api/v1/me')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(deleted.status).toBe(200);
    expect(deleted.body.deleted).toBe(true);

    const me = await request(app.getHttpServer())
      .get('/api/v1/me')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(me.status).toBe(401);
  });

  it('lists and suspends users with the admin API key', async () => {
    if (skip()) return;
    const user = await registerUser(app, '+260977000102', 'adminkey');
    const list = await request(app.getHttpServer())
      .get('/api/v1/admin/users')
      .set('X-Admin-Key', 'test-admin-key');
    expect(list.status).toBe(200);
    expect(list.body.some((u: { id: string }) => u.id === user.userId)).toBe(true);

    const suspended = await request(app.getHttpServer())
      .post(`/api/v1/admin/users/${user.userId}/suspend`)
      .set('X-Admin-Key', 'test-admin-key');
    expect(suspended.status).toBe(200);
    expect(suspended.body.status).toBe('SUSPENDED');

    const denied = await request(app.getHttpServer())
      .get('/api/v1/admin/users')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(denied.status).toBe(401);
  });

  it('exposes request/WS/TURN counters on /health/metrics', async () => {
    if (skip()) return;
    await request(app.getHttpServer()).get('/health/live');
    const metrics = await request(app.getHttpServer()).get('/health/metrics');
    expect(metrics.status).toBe(200);
    expect(metrics.body.http.requests).toBeGreaterThan(0);
    expect(typeof metrics.body.uptimeSeconds).toBe('number');
  });
});
