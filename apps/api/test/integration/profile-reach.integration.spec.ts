/**
 * End-to-end: the one-time name + Viro ID step, and reaching people who have
 * no phone number (find by Viro ID or verified email, connection requests).
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
import { PushService } from '../../src/push/push.service';

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

describe('Profile setup and reaching people without a phone number', () => {
  let app: INestApplication;
  let available = false;
  let phoneUser: User; // signs up by phone
  let emailUser: User; // email only, no phone at all
  let other: User;
  const pushes: { userId: string; data?: Record<string, string> }[] = [];
  const http = () => request(app.getHttpServer());
  const as = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });

  async function phoneSignup(phone: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: phone });
    const v = await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${phone}`,
      platform: 'ANDROID',
      appVersion: '0.4.0',
    });
    return v.body;
  }

  async function emailSignup(email: string): Promise<User> {
    const r = await http().post('/api/v1/auth/email/otp/request').send({ email });
    const v = await http().post('/api/v1/auth/email/otp/verify').send({
      challengeId: r.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${email}`,
      platform: 'ANDROID',
      appVersion: '0.4.0',
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
    const push = app.get(PushService);
    jest.spyOn(push, 'sendToUser').mockImplementation(async (userId, payload) => {
      pushes.push({ userId, data: payload.data });
    });
    phoneUser = await phoneSignup('+260978200001');
    emailUser = await emailSignup('mwila.banda@example.com');
    other = await phoneSignup('+260978200002');
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  it('asks new accounts for a name and Viro ID once', async () => {
    if (skip()) return;
    const me = await http().get('/api/v1/me').set(as(phoneUser)).expect(200);
    expect(me.body.profileCompleted).toBe(false);
    expect(me.body.displayName).toBe('');

    // A name alone can't complete the profile — a Viro ID is required.
    await http().patch('/api/v1/me').set(as(phoneUser)).send({ displayName: 'Chanda', completeProfile: true }).expect(400);
    // Blank names are refused.
    await http().patch('/api/v1/me').set(as(phoneUser)).send({ displayName: '   ' }).expect(400);

    const check = await http().get('/api/v1/me/viro-id/check').query({ name: 'Chanda Mwale' }).set(as(phoneUser)).expect(200);
    expect(check.body.suggestions.length).toBeGreaterThan(0);
    expect(check.body.suggestions[0]).toBe('@chanda.mwale');

    const done = await http().patch('/api/v1/me').set(as(phoneUser))
      .send({ displayName: '  Chanda   Mwale ', viroId: '@Chanda.Mwale', completeProfile: true }).expect(200);
    expect(done.body.profileCompleted).toBe(true);
    expect(done.body.displayName).toBe('Chanda Mwale');
    expect(done.body.viroId).toBe('@chanda.mwale');
  });

  it('reports a taken or malformed Viro ID with free alternatives', async () => {
    if (skip()) return;
    const taken = await http().get('/api/v1/me/viro-id/check').query({ id: 'chanda.mwale' }).set(as(emailUser)).expect(200);
    expect(taken.body).toMatchObject({ valid: true, available: false });
    expect(taken.body.suggestions).not.toContain('@chanda.mwale');
    // …but it's "available" to its owner.
    const own = await http().get('/api/v1/me/viro-id/check').query({ id: 'chanda.mwale' }).set(as(phoneUser)).expect(200);
    expect(own.body.available).toBe(true);

    const bad = await http().get('/api/v1/me/viro-id/check').query({ id: 'a!' }).set(as(emailUser)).expect(200);
    expect(bad.body.valid).toBe(false);
    expect(bad.body.reason).toBeTruthy();

    await http().patch('/api/v1/me').set(as(emailUser)).send({ viroId: 'chanda.mwale' }).expect(409);
  });

  it('shows an email-only account its email and lets it finish setup', async () => {
    if (skip()) return;
    const me = await http().get('/api/v1/me').set(as(emailUser)).expect(200);
    expect(me.body.phoneE164).toBe('');
    expect(me.body.email).toBe('mwila.banda@example.com');
    expect(me.body.emailVerified).toBe(true);
    expect(me.body.discoverableByEmail).toBe(true);
    await http().patch('/api/v1/me').set(as(emailUser))
      .send({ displayName: 'Mwila Banda', viroId: 'mwila', completeProfile: true }).expect(200);
  });

  it('finds people by exact Viro ID or verified email, never revealing contact details', async () => {
    if (skip()) return;
    const byId = await http().get('/api/v1/directory/find').query({ q: '@MWILA' }).set(as(phoneUser)).expect(200);
    expect(byId.body.person).toMatchObject({ userId: emailUser.userId, displayName: 'Mwila Banda', viroId: '@mwila', matchedBy: 'VIRO_ID', connection: null, canCall: false });
    expect(JSON.stringify(byId.body)).not.toContain('example.com');

    const byEmail = await http().get('/api/v1/directory/find').query({ q: ' Mwila.Banda@Example.com ' }).set(as(phoneUser)).expect(200);
    expect(byEmail.body.person).toMatchObject({ userId: emailUser.userId, matchedBy: 'EMAIL' });

    // Exact only: a prefix finds nobody.
    const partial = await http().get('/api/v1/directory/find').query({ q: 'mwi' }).set(as(phoneUser)).expect(200);
    expect(partial.body.person).toBeNull();
    // Yourself isn't a result.
    const self = await http().get('/api/v1/directory/find').query({ q: 'mwila' }).set(as(emailUser)).expect(200);
    expect(self.body.person).toBeNull();
    // Garbage is a clear error, not a silent miss.
    await http().get('/api/v1/directory/find').query({ q: 'not an id!' }).set(as(phoneUser)).expect(400);
  });

  it('respects "find me by email" being switched off', async () => {
    if (skip()) return;
    await http().patch('/api/v1/me').set(as(emailUser)).send({ discoverableByEmail: false }).expect(200);
    const hidden = await http().get('/api/v1/directory/find').query({ q: 'mwila.banda@example.com' }).set(as(other)).expect(200);
    expect(hidden.body.person).toBeNull();
    // The Viro ID still works.
    const byId = await http().get('/api/v1/directory/find').query({ q: 'mwila' }).set(as(other)).expect(200);
    expect(byId.body.person?.userId).toBe(emailUser.userId);
    await http().patch('/api/v1/me').set(as(emailUser)).send({ discoverableByEmail: true }).expect(200);
  });

  it('connects: request → push → accept → both can call', async () => {
    if (skip()) return;
    pushes.length = 0;
    // Before connecting: no phone-contact match, so the call is refused.
    const before = await http().post("/api/v1/calls/authorize").set(as(phoneUser)).send({ targetUserId: emailUser.userId });
    expect(before.status).toBe(403);

    const req = await http().post('/api/v1/connections').set(as(phoneUser)).send({ targetUserId: emailUser.userId }).expect(201);
    expect(req.body).toMatchObject({ status: 'PENDING', direction: 'OUTGOING', peerDisplayName: 'Mwila Banda', peerViroId: '@mwila' });
    expect(pushes).toContainEqual(expect.objectContaining({ userId: emailUser.userId, data: expect.objectContaining({ type: 'connection_request' }) }));

    // Asking again doesn't spam.
    await http().post('/api/v1/connections').set(as(phoneUser)).send({ targetUserId: emailUser.userId }).expect(201);
    expect(pushes.filter((p) => p.data?.type === 'connection_request')).toHaveLength(1);

    const incoming = await http().get('/api/v1/connections').set(as(emailUser)).expect(200);
    expect(incoming.body[0]).toMatchObject({ direction: 'INCOMING', status: 'PENDING', peerUserId: phoneUser.userId, peerDisplayName: 'Chanda Mwale' });

    await http().post(`/api/v1/connections/${incoming.body[0].id}/accept`).set(as(emailUser)).expect(201);
    expect(pushes).toContainEqual(expect.objectContaining({ userId: phoneUser.userId, data: expect.objectContaining({ type: 'connection_accepted' }) }));

    const found = await http().get('/api/v1/directory/find').query({ q: 'mwila' }).set(as(phoneUser)).expect(200);
    expect(found.body.person.connection).toMatchObject({ status: 'ACCEPTED' });
    expect(found.body.person.canCall).toBe(true);

    // The call path agrees: an email-only person can now be called.
    const call = await http().post("/api/v1/calls/authorize").set(as(phoneUser)).send({ targetUserId: emailUser.userId });
    expect(call.status).not.toBe(403);
  });

  it('auto-accepts mutual requests and never reveals a decline', async () => {
    if (skip()) return;
    // other → emailUser, then emailUser asks back: that's a yes.
    await http().post('/api/v1/connections').set(as(other)).send({ targetUserId: emailUser.userId }).expect(201);
    const back = await http().post('/api/v1/connections').set(as(emailUser)).send({ targetUserId: other.userId }).expect(201);
    expect(back.body.status).toBe('ACCEPTED');

    // A decline looks like "still pending" to the requester.
    const x = await http().get('/api/v1/directory/find').query({ q: 'chanda.mwale' }).set(as(other)).expect(200);
    const r = await http().post('/api/v1/connections').set(as(other)).send({ targetUserId: x.body.person.userId }).expect(201);
    await http().post(`/api/v1/connections/${r.body.id}/reject`).set(as(phoneUser)).expect(201);
    const view = await http().get('/api/v1/connections').set(as(other)).expect(200);
    expect(view.body.find((c: any) => c.id === r.body.id).status).toBe('PENDING');
    const again = await http().get('/api/v1/directory/find').query({ q: 'chanda.mwale' }).set(as(other)).expect(200);
    expect(again.body.person.connection.status).toBe('PENDING');
    // And the decliner no longer sees it.
    const theirs = await http().get('/api/v1/connections').set(as(phoneUser)).expect(200);
    expect(theirs.body.find((c: any) => c.id === r.body.id)).toBeUndefined();
  });

  it('hides blocked people from Find people', async () => {
    if (skip()) return;
    await http().post('/api/v1/blocks').set(as(emailUser)).send({ blockedUserId: other.userId });
    const res = await http().get('/api/v1/directory/find').query({ q: 'mwila' }).set(as(other)).expect(200);
    expect(res.body.person).toBeNull();
  });

  it('serves a public invite page that reveals nothing but the handle', async () => {
    if (skip()) return;
    const page = await http().get('/api/v1/invite/mwila').expect(200);
    expect(page.text).toContain('@mwila');
    expect(page.text).toContain('viro://u/mwila');
    expect(page.text).not.toContain('Mwila Banda');
    await http().get('/api/v1/invite/%21%21').expect(404);
  });
});
