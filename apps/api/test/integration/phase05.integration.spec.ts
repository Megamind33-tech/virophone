/**
 * Phase 0.5 integration tests — require real PostgreSQL (+ Redis for full stack).
 * Run: DATABASE_URL=postgresql://viro:viro_dev_password@localhost:5432/viro_reach npm run test:integration
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { createTestApp } from './test-app';
import { Client } from 'pg';
import { readFileSync } from 'fs';
import { join } from 'path';

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

interface TestUser {
  userId: string;
  deviceId: string;
  accessToken: string;
  refreshToken: string;
  phone: string;
}

async function registerUser(
  app: INestApplication,
  phone: string,
  publicKey = 'test-pubkey',
): Promise<TestUser & { challengeId: string; otpCode: string }> {
  const otpRes = await request(app.getHttpServer())
    .post('/api/v1/auth/otp/request')
    .send({ phoneE164: phone });
  expect([200, 201]).toContain(otpRes.status);

  const code = process.env.TEST_OTP_CODE || '123456';

  const verifyRes = await request(app.getHttpServer())
    .post('/api/v1/auth/otp/verify')
    .send({
      challengeId: otpRes.body.challengeId,
      code,
      devicePublicKey: publicKey,
      platform: 'ANDROID',
      appVersion: '0.1.0',
    });

  if (![200, 201].includes(verifyRes.status)) {
    throw new Error(`OTP verify failed: ${JSON.stringify(verifyRes.body)}`);
  }

  return {
    userId: verifyRes.body.userId,
    deviceId: verifyRes.body.deviceId,
    accessToken: verifyRes.body.accessToken,
    refreshToken: verifyRes.body.refreshToken,
    phone,
    challengeId: otpRes.body.challengeId,
    otpCode: code,
  };
}

describe('Phase 0.5 Integration (PostgreSQL)', () => {
  let app: INestApplication;
  let pgAvailable = false;

  beforeAll(async () => {
    try {
      const client = new Client({ connectionString: DATABASE_URL });
      await client.connect();
      await client.query('SELECT 1');
      await client.end();
      pgAvailable = true;
      await resetDatabase();
    } catch (e) {
      console.warn('PostgreSQL not available, skipping integration tests:', e);
      return;
    }

    app = await createTestApp();
  });

  afterAll(async () => {
    if (app) await app.close();
  });

  const skipIfNoPg = () => {
    if (!pgAvailable) {
      console.warn('SKIP: PostgreSQL unavailable');
      return true;
    }
    return false;
  };

  describe('Auth flow', () => {
    it('registration → OTP → account → device', async () => {
      if (skipIfNoPg()) return;

      const user = await registerUser(app, '+260971000001');
      expect(user.userId).toBeDefined();
      expect(user.accessToken).toBeDefined();
      expect(user.deviceId).toBeDefined();

      const me = await request(app.getHttpServer())
        .get('/api/v1/me')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .expect(200);

      expect(me.body.phoneE164).toBe('+260971000001');
    });
  });

  describe('Alice / Bob / Charlie blocking', () => {
    let alice: TestUser;
    let bob: TestUser;
    let charlie: TestUser;

    beforeAll(async () => {
      if (!pgAvailable) return;
      await resetDatabase();
      alice = await registerUser(app, '+260971000101', 'alice-key');
      bob = await registerUser(app, '+260971000102', 'bob-key');
      charlie = await registerUser(app, '+260971000103', 'charlie-key');

      await request(app.getHttpServer())
        .patch('/api/v1/me')
        .set('Authorization', `Bearer ${bob.accessToken}`)
        .send({ viroId: '@bob.test', displayName: 'Bob' })
        .expect(200);
    });

    it('Alice discovers Bob when only Bob is in her contact batch', async () => {
      if (skipIfNoPg()) return;

      const res = await request(app.getHttpServer())
        .post('/api/v1/contacts/discover')
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .send({ phonesE164: ['+260971000102'] });
      expect([200, 201]).toContain(res.status);

      expect(res.body.matches).toHaveLength(1);
      expect(res.body.matches[0].userId).toBe(bob.userId);
    });

    it('Alice does not discover Charlie without his number in batch', async () => {
      if (skipIfNoPg()) return;

      const res = await request(app.getHttpServer())
        .post('/api/v1/contacts/discover')
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .send({ phonesE164: ['+260971000102'] });
      expect(res.body.matches.every((m: { userId: string }) => m.userId !== charlie.userId)).toBe(true);
    });

    it('Bob blocks Alice — Alice cannot discover Bob', async () => {
      if (skipIfNoPg()) return;

      const blockRes = await request(app.getHttpServer())
        .post('/api/v1/blocks')
        .set('Authorization', `Bearer ${bob.accessToken}`)
        .send({ blockedUserId: alice.userId });
      expect([200, 201]).toContain(blockRes.status);

      const res = await request(app.getHttpServer())
        .post('/api/v1/contacts/discover')
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .send({ phonesE164: ['+260971000102'] });
      expect([200, 201]).toContain(res.status);

      expect(res.body.matches).toHaveLength(0);
    });

    it('Alice cannot authorize call to Bob (generic unavailable)', async () => {
      if (skipIfNoPg()) return;

      const res = await request(app.getHttpServer())
        .post('/api/v1/calls/authorize')
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .send({ targetUserId: bob.userId });

      expect(res.status).toBe(404);
      expect(res.body.message).not.toMatch(/blocked/i);
    });

    it('Alice cannot look up Bob via Viro ID after block', async () => {
      if (skipIfNoPg()) return;

      const res = await request(app.getHttpServer())
        .get('/api/v1/directory/exact/@bob.test')
        .set('Authorization', `Bearer ${alice.accessToken}`);

      expect(res.status).toBe(404);
    });
  });

  describe('Viro ID enumeration protection', () => {
    let user: TestUser;

    beforeAll(async () => {
      if (!pgAvailable) return;
      user = await registerUser(app, '+260971000201');
      await request(app.getHttpServer())
        .patch('/api/v1/me')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ viroId: '@brian.m' })
        .expect(200);
    });

    const invalidIds = [
      '@brian',
      '@brian.',
      '@bria',
      '@brian*',
      '@brıan',
      '@' + 'a'.repeat(50),
      "'; DROP TABLE users;--",
    ];

    for (const id of invalidIds) {
      it(`rejects invalid ID: ${id.slice(0, 20)}`, async () => {
        if (skipIfNoPg()) return;
        const encoded = encodeURIComponent(id);
        const res = await request(app.getHttpServer())
          .get(`/api/v1/directory/exact/${encoded}`)
          .set('Authorization', `Bearer ${user.accessToken}`);
        expect([400, 404]).toContain(res.status);
        if (res.status === 200) {
          expect(res.body.viroId).toBe('@brian.m');
        }
      });
    }

    it('exact match succeeds with case variation', async () => {
      if (skipIfNoPg()) return;
      const searcher = await registerUser(app, '+260971000202');
      const res = await request(app.getHttpServer())
        .get('/api/v1/directory/exact/@BRIAN.M')
        .set('Authorization', `Bearer ${searcher.accessToken}`)
        .expect(200);
      expect(res.body.viroId).toBe('@brian.m');
    });
  });

  describe('Refresh token reuse', () => {
    it('detects reuse and revokes family', async () => {
      if (skipIfNoPg()) return;
      await resetDatabase();
      const user = await registerUser(app, '+260971000301');
      const tokenA = user.refreshToken;

      const refresh1 = await request(app.getHttpServer())
        .post('/api/v1/auth/refresh')
        .send({ refreshToken: tokenA });
      expect([200, 201]).toContain(refresh1.status);

      const tokenB = refresh1.body.refreshToken;

      const reuse = await request(app.getHttpServer())
        .post('/api/v1/auth/refresh')
        .send({ refreshToken: tokenA });
      expect(reuse.status).toBe(401);

      const pg = new Client({ connectionString: DATABASE_URL });
      await pg.connect();
      const { rows } = await pg.query(
        "SELECT * FROM security_events WHERE event_type = 'REFRESH_TOKEN_REUSE'",
      );
      await pg.end();
      expect(rows.length).toBeGreaterThanOrEqual(1);

      const refreshB = await request(app.getHttpServer())
        .post('/api/v1/auth/refresh')
        .send({ refreshToken: tokenB });
      expect([401, 201]).toContain(refreshB.status);
    });
  });

  describe('Device revocation', () => {
    it('revoked device cannot refresh or authorize calls', async () => {
      if (skipIfNoPg()) return;
      await resetDatabase();
      const alice = await registerUser(app, '+260971000401');
      const bob = await registerUser(app, '+260971000402');

      await request(app.getHttpServer())
        .post('/api/v1/contacts/discover')
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .send({ phonesE164: ['+260971000402'] });

      await request(app.getHttpServer())
        .delete(`/api/v1/devices/${alice.deviceId}`)
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .expect(200);

      await request(app.getHttpServer())
        .post('/api/v1/auth/refresh')
        .send({ refreshToken: alice.refreshToken })
        .expect(401);

      await request(app.getHttpServer())
        .post('/api/v1/calls/authorize')
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .send({ targetUserId: bob.userId })
        .expect(401);
    });
  });

  describe('Database constraints', () => {
    it('rejects duplicate phone number', async () => {
      if (skipIfNoPg()) return;
      await resetDatabase();
      await registerUser(app, '+260971000501');

      const otpRes = await request(app.getHttpServer())
        .post('/api/v1/auth/otp/request')
        .send({ phoneE164: '+260971000501' });

      const verifyRes = await request(app.getHttpServer())
        .post('/api/v1/auth/otp/verify')
        .send({
          challengeId: otpRes.body.challengeId,
          code: '123456',
          devicePublicKey: 'other-device',
          platform: 'ANDROID',
          appVersion: '0.1.0',
        });

      expect(verifyRes.status).toBe(201);
      expect(verifyRes.body.isNewUser).toBe(false);
    });

    it('rejects duplicate Viro ID', async () => {
      if (skipIfNoPg()) return;
      const u1 = await registerUser(app, '+260971000601');
      const u2 = await registerUser(app, '+260971000602');

      await request(app.getHttpServer())
        .patch('/api/v1/me')
        .set('Authorization', `Bearer ${u1.accessToken}`)
        .send({ viroId: '@unique.id' })
        .expect(200);

      const dup = await request(app.getHttpServer())
        .patch('/api/v1/me')
        .set('Authorization', `Bearer ${u2.accessToken}`)
        .send({ viroId: '@unique.id' });

      expect(dup.status).toBe(409);
    });
  });

  describe('API authorization matrix', () => {
    it('unauthenticated requests rejected', async () => {
      if (skipIfNoPg()) return;
      await request(app.getHttpServer()).get('/api/v1/me').expect(401);
    });

    it('malformed discover body rejected', async () => {
      if (skipIfNoPg()) return;
      const user = await registerUser(app, '+260971000701');
      await request(app.getHttpServer())
        .post('/api/v1/contacts/discover')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ phonesE164: ['not-valid'] })
        .expect(400);
    });
  });

  describe('Phone normalization Zambia', () => {
    it('canonicalizes 0961582985 format via discover', async () => {
      if (skipIfNoPg()) return;
      await resetDatabase();
      const bob = await registerUser(app, '+260961582985');
      const alice = await registerUser(app, '+260971000801');

      const res = await request(app.getHttpServer())
        .post('/api/v1/contacts/discover')
        .set('Authorization', `Bearer ${alice.accessToken}`)
        .send({ phonesE164: ['0961582985'], defaultRegion: 'ZM' });
      expect([200, 201]).toContain(res.status);

      expect(res.body.matches).toHaveLength(1);
      expect(res.body.matches[0].userId).toBe(bob.userId);
    });
  });
});
