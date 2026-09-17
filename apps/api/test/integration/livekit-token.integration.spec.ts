/**
 * Checkpoint B — LiveKit token issuance authorization.
 *
 * Verifies:
 *  1. authenticated caller/callee can request credentials for their call;
 *  2. an unauthorized third user cannot obtain credentials;
 *  3. caller and callee receive credentials for the SAME room;
 *  4. an ended call cannot obtain new tokens;
 *  5. tokens carry the correct participant identity and permissions;
 *  6. LIVEKIT_API_SECRET never appears in the HTTP response.
 *
 * All assertions share ONE call (registered once in beforeAll) — the OTP
 * request endpoint is hard-throttled to 5 requests/5min regardless of test
 * env config (see auth.controller.ts @Throttle), so this file intentionally
 * registers as few users as possible instead of one call per test case. The
 * call-ended assertion runs last since it terminates the shared call.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { readFileSync } from 'fs';
import { join } from 'path';
import { TokenVerifier } from 'livekit-server-sdk';
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

/** Makes caller allowed to call callee — mirrors what contact discovery would produce. */
async function allowCall(callerId: string, calleeId: string) {
  const client = new Client({ connectionString: DATABASE_URL });
  await client.connect();
  await client.query(
    `INSERT INTO contact_matches (user_id, matched_user_id, phone_hash, expires_at)
     VALUES ($1, $2, 'test-hash', now() + interval '1 day')`,
    [callerId, calleeId],
  );
  await client.end();
}

type Session = { userId: string; deviceId: string; accessToken: string };

describe('LiveKit token issuance (Checkpoint B)', () => {
  let app: INestApplication;
  let available = false;
  let caller: Session;
  let callee: Session;
  let outsider: Session;
  let callId: string;

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

    caller = await registerUser(app, '+260971000001', 'lk-caller');
    callee = await registerUser(app, '+260971000002', 'lk-callee');
    outsider = await registerUser(app, '+260971000003', 'lk-outsider');
    await allowCall(caller.userId, callee.userId);
    const auth = await request(app.getHttpServer())
      .post('/api/v1/calls/authorize')
      .set('Authorization', `Bearer ${caller.accessToken}`)
      .send({ targetUserId: callee.userId });
    expect(auth.status).toBe(201);
    callId = auth.body.callId;
  });

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => !available;

  function requestToken(token: string) {
    return request(app.getHttpServer())
      .post(`/api/v1/calls/${callId}/livekit-token`)
      .set('Authorization', `Bearer ${token}`)
      .send();
  }

  it('lets the caller obtain a room-scoped token', async () => {
    if (skip()) return;
    const res = await requestToken(caller.accessToken);
    expect(res.status).toBe(201);
    expect(res.body.token).toBeTruthy();
    expect(res.body.url).toBe(process.env.LIVEKIT_URL);
    expect(res.body.roomName).toContain(callId);
  });

  it('lets the callee obtain a token for the SAME room as the caller', async () => {
    if (skip()) return;
    const callerRes = await requestToken(caller.accessToken);
    const calleeRes = await requestToken(callee.accessToken);
    expect(callerRes.status).toBe(201);
    expect(calleeRes.status).toBe(201);
    expect(callerRes.body.roomName).toBe(calleeRes.body.roomName);

    const verifier = new TokenVerifier(
      process.env.LIVEKIT_API_KEY!,
      process.env.LIVEKIT_API_SECRET!,
    );
    const callerClaims = await verifier.verify(callerRes.body.token);
    const calleeClaims = await verifier.verify(calleeRes.body.token);
    expect(callerClaims.video?.room).toBe(calleeClaims.video?.room);
    expect(callerClaims.sub).toBe(caller.userId);
    expect(calleeClaims.sub).toBe(callee.userId);
  });

  it('issues a token with room-join, publish and subscribe permissions, audio only', async () => {
    if (skip()) return;
    const res = await requestToken(caller.accessToken);
    const verifier = new TokenVerifier(
      process.env.LIVEKIT_API_KEY!,
      process.env.LIVEKIT_API_SECRET!,
    );
    const claims = await verifier.verify(res.body.token);
    expect(claims.video?.roomJoin).toBe(true);
    expect(claims.video?.canPublish).toBe(true);
    expect(claims.video?.canSubscribe).toBe(true);
    // Voice only — no video grant yet.
    expect(claims.video?.canPublishSources).toEqual(['microphone']);
  });

  it('refuses a token to a user who is not a participant on the call', async () => {
    if (skip()) return;
    const res = await requestToken(outsider.accessToken);
    expect(res.status).toBe(403);
  });

  it('never returns LIVEKIT_API_SECRET in the response body', async () => {
    if (skip()) return;
    const res = await requestToken(caller.accessToken);
    expect(JSON.stringify(res.body)).not.toContain(process.env.LIVEKIT_API_SECRET);
  });

  it('rejects an unauthenticated request outright', async () => {
    if (skip()) return;
    const res = await request(app.getHttpServer())
      .post(`/api/v1/calls/${callId}/livekit-token`)
      .send();
    expect(res.status).toBe(401);
  });

  // Must run last: terminates the shared call.
  it('refuses a token once the call has ended', async () => {
    if (skip()) return;
    const end = await request(app.getHttpServer())
      .post(`/api/v1/calls/${callId}/end`)
      .set('Authorization', `Bearer ${caller.accessToken}`)
      .send();
    expect(end.status).toBe(201);
    const res = await requestToken(caller.accessToken);
    expect(res.status).toBe(404);
  });
});
