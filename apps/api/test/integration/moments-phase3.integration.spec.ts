jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { RealtimeRegistry } from '../../src/realtime/realtime.registry';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string; deviceId: string };

/**
 * Phase 3: the three things Phase 2 left open — a room chat the server cannot
 * read, a reaction to the Moment itself, and an audience the host can change
 * while the Moment is running.
 *
 * The ciphertext here is not real libsignal output and does not need to be:
 * what these tests are about is that the server stores what it is given
 * without reading it, addresses it only to devices in the room, and hands each
 * device nothing but its own copy.
 */
describe('Viro Now Phase 3: sealed rooms, Moment reactions, audience editing', () => {
  let app: INestApplication;
  let db: Client;
  let a: User; let b: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });
  const create = async (u = a, overrides = {}) =>
    (await http().post('/api/v1/moments').set(auth(u))
      .send({ type: 'WATCHING', visibility: 'CONNECTIONS', durationMinutes: 15, ...overrides }).expect(201)).body;
  const join = async (u: User, id: string) =>
    (await http().post(`/api/v1/moments/${id}/join`).set(auth(u)).expect(200)).body;
  const roomOf = async (u: User, id: string, status = 200) =>
    (await http().get(`/api/v1/moments/${id}/room`).set(auth(u)).expect(status)).body;
  const nowOf = async (u: User) =>
    (await http().get('/api/v1/moments/now').set(auth(u)).expect(200)).body.moments;

  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId, code: '123456',
      devicePublicKey: `phase3-${n}`, platform: 'ANDROID', appVersion: 'phase3-test',
    }).expect(201)).body;
  }

  beforeAll(async () => {
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    a = await register('+260978730001');
    b = await register('+260978730002');
    stranger = await register('+260978730003');
    await db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`,
      [a.userId, b.userId]);
  }, 120000);
  afterAll(async () => { if (app) await app.close(); if (db) await db.end(); });
  beforeEach(async () => {
    await db.query('DELETE FROM moments');
    await db.query('DELETE FROM blocks');
    await db.query('DELETE FROM contact_matches');
    await db.query(`UPDATE viro_connections SET status = 'ACCEPTED'`);
  });

  // ------------------------------------------------------------ sealed chat

  it('stores a sealed room message as ciphertext with no readable body', async () => {
    const m = await create();
    await join(b, m.id);
    const sent = await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a)).send({
      envelopes: [
        { deviceId: b.deviceId, ciphertext: 'for-b', type: 3 },
        { deviceId: a.deviceId, ciphertext: 'for-a', type: 3 },
      ],
    }).expect(201);
    expect(sent.body.sealed).toBe(true);
    expect(sent.body.body).toBeNull();

    const stored = await db.query('SELECT body, sender_device_id FROM moment_messages WHERE id = $1', [sent.body.id]);
    expect(stored.rows[0].body).toBeNull();
    expect(stored.rows[0].sender_device_id).toBe(a.deviceId);
    const envelopes = await db.query('SELECT device_id, ciphertext FROM moment_message_envelopes WHERE message_id = $1', [sent.body.id]);
    expect(envelopes.rows).toHaveLength(2);
  });

  it('hands each device only its own copy', async () => {
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a)).send({
      envelopes: [
        { deviceId: b.deviceId, ciphertext: 'for-b' },
        { deviceId: a.deviceId, ciphertext: 'for-a' },
      ],
    }).expect(201);

    const forB = (await roomOf(b, m.id)).messages;
    expect(forB).toHaveLength(1);
    expect(forB[0].sealed).toBe(true);
    expect(forB[0].body).toBeNull();
    expect(forB[0].envelope.ciphertext).toBe('for-b');
    expect(forB[0].senderDeviceId).toBe(a.deviceId);

    const forA = (await roomOf(a, m.id)).messages;
    expect(forA[0].envelope.ciphertext).toBe('for-a');
  });

  it('refuses to hold a copy for a device that is not in the room', async () => {
    const m = await create();
    // b never joined, so no copy may be left here for b's device.
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a))
      .send({ envelopes: [{ deviceId: b.deviceId, ciphertext: 'for-b' }] }).expect(400);

    // With one addressable device among several, only that one is kept.
    await join(b, m.id);
    const sent = await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a)).send({
      envelopes: [
        { deviceId: b.deviceId, ciphertext: 'for-b' },
        { deviceId: stranger.deviceId, ciphertext: 'for-stranger' },
      ],
    }).expect(201);
    const kept = await db.query('SELECT device_id FROM moment_message_envelopes WHERE message_id = $1', [sent.body.id]);
    expect(kept.rows.map((r: any) => r.device_id)).toEqual([b.deviceId]);
  });

  it('keeps an unreadable placeholder without exposing another devices sealed copy', async () => {
    const m = await create();
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a))
      .send({ envelopes: [{ deviceId: a.deviceId, ciphertext: 'only-for-a' }] }).expect(201);
    // b joins afterwards: there is no copy for b and no way to make one.
    const late = await join(b, m.id);
    expect(late.messages).toHaveLength(1);
    expect(late.messages[0]).toMatchObject({ sealed: true, body: null, envelope: null });
    // The host still sees what they said.
    expect((await roomOf(a, m.id)).messages).toHaveLength(1);
  });

  it('still accepts a plaintext message, and never both at once', async () => {
    const m = await create();
    await join(b, m.id);
    const plain = await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a))
      .send({ body: 'in the clear' }).expect(201);
    expect(plain.body.sealed).toBe(false);
    expect(plain.body.body).toBe('in the clear');

    // A message with both is stored sealed: the body is dropped, not kept.
    const both = await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a))
      .send({ body: 'readable', envelopes: [{ deviceId: b.deviceId, ciphertext: 'sealed' }] }).expect(201);
    const stored = await db.query('SELECT body FROM moment_messages WHERE id = $1', [both.body.id]);
    expect(stored.rows[0].body).toBeNull();

    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a)).send({}).expect(400);
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a)).send({ body: '   ' }).expect(400);
  });

  it('sends a sealed message device by device, never to the whole room', async () => {
    const m = await create();
    await join(b, m.id);
    const toUser = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    const toDevice = jest.spyOn(app.get(RealtimeRegistry), 'deliverToDevice');
    try {
      await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a))
        .send({ envelopes: [{ deviceId: b.deviceId, ciphertext: 'for-b' }] }).expect(201);
      expect(toUser.mock.calls.some((c) => (c[1] as any)?.type === 'moment.message')).toBe(false);
      const frame = toDevice.mock.calls.find((c) => (c[1] as any)?.type === 'moment.message');
      expect(frame?.[0]).toBe(b.deviceId);
      const message = (frame?.[1] as any).payload.message;
      expect(message.body).toBeNull();
      expect(message.envelope.ciphertext).toBe('for-b');
    } finally {
      toUser.mockRestore(); toDevice.mockRestore();
    }
  });

  it('erases envelopes with the room when the Moment ends', async () => {
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a))
      .send({ envelopes: [{ deviceId: b.deviceId, ciphertext: 'for-b' }] }).expect(201);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const left = await db.query('SELECT count(*)::int AS n FROM moment_message_envelopes');
    expect(left.rows[0].n).toBe(0);
  });

  // ------------------------------------------------- reactions to a Moment

  it('counts reactions to the Moment itself and ranks them', async () => {
    const m = await create();
    expect(m.reactions).toEqual([]);
    expect(m.reactionCount).toBe(0);

    await http().post(`/api/v1/moments/${m.id}/react`).set(auth(b)).send({ emoji: '🔥' }).expect(200);
    await http().post(`/api/v1/moments/${m.id}/react`).set(auth(a)).send({ emoji: '🔥' }).expect(200);
    const mine = (await nowOf(b)).find((x: any) => x.id === m.id);
    expect(mine.reactions).toEqual([{ emoji: '🔥', count: 2 }]);
    expect(mine.reactionCount).toBe(2);
    expect(mine.myReaction).toBe('🔥');

    // One live reaction each: switching replaces rather than adds.
    await http().post(`/api/v1/moments/${m.id}/react`).set(auth(b)).send({ emoji: '👏' }).expect(200);
    const after = (await nowOf(b)).find((x: any) => x.id === m.id);
    expect(after.reactionCount).toBe(2);
    expect(after.reactions[0]).toEqual({ emoji: '🔥', count: 1 });
    expect(after.myReaction).toBe('👏');

    // null takes it back.
    await http().post(`/api/v1/moments/${m.id}/react`).set(auth(b)).send({ emoji: null }).expect(200);
    const cleared = (await nowOf(b)).find((x: any) => x.id === m.id);
    expect(cleared.reactionCount).toBe(1);
    expect(cleared.myReaction).toBeNull();
  });

  it('refuses reactions from people who cannot see the Moment, and emoji outside the set', async () => {
    const m = await create();
    await http().post(`/api/v1/moments/${m.id}/react`).set(auth(stranger)).send({ emoji: '🔥' }).expect(404);
    await http().post(`/api/v1/moments/${m.id}/react`).set(auth(b)).send({ emoji: '🦄' }).expect(400);
    await http().post(`/api/v1/moments/${m.id}/react`).send({ emoji: '🔥' }).expect(401);
  });

  it('clears reactions with the room', async () => {
    const m = await create();
    await http().post(`/api/v1/moments/${m.id}/react`).set(auth(b)).send({ emoji: '🔥' }).expect(200);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
    const left = await db.query('SELECT count(*)::int AS n FROM moment_cheers');
    expect(left.rows[0].n).toBe(0);
  });

  // ------------------------------------------------------ audience editing

  it('lets the host narrow the audience of a running Moment, and only the host', async () => {
    const m = await create();
    expect((await nowOf(b)).some((x: any) => x.id === m.id)).toBe(true);

    await http().patch(`/api/v1/moments/${m.id}/visibility`).set(auth(b))
      .send({ visibility: 'CONTACTS' }).expect(403);

    const changed = await http().patch(`/api/v1/moments/${m.id}/visibility`).set(auth(a))
      .send({ visibility: 'CONTACTS' }).expect(200);
    expect(changed.body.visibility).toBe('CONTACTS');
    expect(changed.body.visibilityChangedAt).not.toBeNull();

    // b is a connection, not a contact match, so the Moment leaves their feed
    // and their room with it.
    expect((await nowOf(b)).some((x: any) => x.id === m.id)).toBe(false);
    await roomOf(b, m.id, 404);
  });

  it('widens the audience and announces it to the people who can now see it', async () => {
    const m = await create(a, { visibility: 'CONTACTS' });
    expect((await nowOf(b)).some((x: any) => x.id === m.id)).toBe(false);

    const deliver = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    try {
      await http().patch(`/api/v1/moments/${m.id}/visibility`).set(auth(a))
        .send({ visibility: 'CONNECTIONS' }).expect(200);
      expect(deliver.mock.calls.some((c) => c[0] === b.userId && (c[1] as any)?.type === 'moment.updated')).toBe(true);
    } finally {
      deliver.mockRestore();
    }
    expect((await nowOf(b)).some((x: any) => x.id === m.id)).toBe(true);
  });

  it('rejects an audience that is not one of the two, and is a no-op when unchanged', async () => {
    const m = await create();
    await http().patch(`/api/v1/moments/${m.id}/visibility`).set(auth(a))
      .send({ visibility: 'PUBLIC' }).expect(400);
    const same = await http().patch(`/api/v1/moments/${m.id}/visibility`).set(auth(a))
      .send({ visibility: 'CONNECTIONS' }).expect(200);
    expect(same.body.visibilityChangedAt).toBeNull();
  });
});
