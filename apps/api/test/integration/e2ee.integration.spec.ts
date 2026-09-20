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

describe('End-to-end encryption (server)', () => {
  let app: INestApplication;
  let available = false;
  let alicePhone: Session;
  let bobPhone: Session;
  let bobLaptop: Session;
  let carol: Session;
  const http = () => request(app.getHttpServer());
  const as = (s: Session) => ({ Authorization: `Bearer ${s.accessToken}` });

  async function signIn(phone: string): Promise<Session> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: phone });
    const v = await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${phone}-${Math.random()}`,
      platform: 'ANDROID',
      appVersion: '0.4.72',
    });
    return v.body;
  }

  /** Publishes a plausible bundle for one device. */
  async function publishKeys(s: Session, prekeyIds = [1, 2, 3]) {
    return http()
      .post('/api/v1/keys')
      .set(as(s))
      .send({
        registrationId: 4242,
        identityKey: seal(`identity-${s.deviceId}`),
        signedPreKey: {
          keyId: 7,
          publicKey: seal(`signed-${s.deviceId}`),
          signature: seal(`sig-${s.deviceId}`),
        },
        kyberPreKey: {
          keyId: 9,
          publicKey: seal(`kyber-${s.deviceId}`),
          signature: seal(`kyber-sig-${s.deviceId}`),
        },
        oneTimePreKeys: prekeyIds.map((keyId) => ({ keyId, publicKey: seal(`otp-${keyId}-${s.deviceId}`) })),
      });
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
    alicePhone = await signIn('+260978811001');
    bobPhone = await signIn('+260978811002');
    // The same person signing in again: a second device for Bob.
    bobLaptop = await signIn('+260978811002');
    carol = await signIn('+260978811003');
    await publishKeys(alicePhone);
    await publishKeys(bobPhone);
    await publishKeys(bobLaptop);
    // Carol deliberately publishes nothing: an app that cannot encrypt yet.
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  // ------------------------------------------------------------ key directory

  it('hands back a bundle for every device the person uses', async () => {
    if (skip()) return;
    const res = await http().get(`/api/v1/keys/${bobPhone.userId}`).set(as(alicePhone)).expect(200);
    const ids = res.body.devices.map((d: any) => d.deviceId).sort();
    expect(ids).toEqual([bobPhone.deviceId, bobLaptop.deviceId].sort());
    const one = res.body.devices[0];
    expect(one.registrationId).toBe(4242);
    expect(one.identityKey).toBeTruthy();
    expect(one.signedPreKey.signature).toBeTruthy();
    // Without the Kyber half a current client cannot start a session at all.
    expect(one.kyberPreKey.keyId).toBe(9);
    expect(one.kyberPreKey.publicKey).toBeTruthy();
    expect(one.kyberPreKey.signature).toBeTruthy();
    expect(one.preKey.keyId).toBeGreaterThan(0);
  });

  it('never hands the same one-time prekey out twice', async () => {
    if (skip()) return;
    const seen = new Set<number>();
    // Bob's phone published three; ask four times.
    for (let i = 0; i < 4; i++) {
      const res = await http().get(`/api/v1/keys/${bobPhone.userId}`).set(as(alicePhone)).expect(200);
      const phone = res.body.devices.find((d: any) => d.deviceId === bobPhone.deviceId);
      if (!phone.preKey) continue;
      expect(seen.has(phone.preKey.keyId)).toBe(false);
      seen.add(phone.preKey.keyId);
    }
    expect(seen.size).toBeLessThanOrEqual(3);
    // And the fourth ask gets no prekey at all rather than a repeat.
    const empty = await http().get(`/api/v1/keys/${bobPhone.userId}`).set(as(alicePhone)).expect(200);
    expect(empty.body.devices.find((d: any) => d.deviceId === bobPhone.deviceId).preKey).toBeNull();
  });

  it('lists someone\'s devices without spending a prekey', async () => {
    if (skip()) return;
    // Alice has published three one-time prekeys and none have been asked for.
    const before = await http().get('/api/v1/keys/status').set(as(alicePhone)).expect(200);
    const list = await http().get(`/api/v1/keys/${alicePhone.userId}/devices`).set(as(bobPhone)).expect(200);
    expect(list.body.deviceIds).toEqual([alicePhone.deviceId]);
    const after = await http().get('/api/v1/keys/status').set(as(alicePhone)).expect(200);
    expect(after.body.available).toBe(before.body.available);
  });

  it('fetches a bundle only for the devices the sender names', async () => {
    if (skip()) return;
    const res = await http()
      .get(`/api/v1/keys/${bobPhone.userId}`)
      .query({ devices: bobLaptop.deviceId })
      .set(as(alicePhone))
      .expect(200);
    expect(res.body.devices.map((d: any) => d.deviceId)).toEqual([bobLaptop.deviceId]);
  });

  it('lets a device see how many it has left, and top up', async () => {
    if (skip()) return;
    const before = await http().get('/api/v1/keys/status').set(as(bobPhone)).expect(200);
    expect(before.body.available).toBe(0);
    await http()
      .post('/api/v1/keys/prekeys')
      .set(as(bobPhone))
      .send({ oneTimePreKeys: [{ keyId: 10, publicKey: seal('otp-10') }, { keyId: 11, publicKey: seal('otp-11') }] })
      .expect(201);
    const after = await http().get('/api/v1/keys/status').set(as(bobPhone)).expect(200);
    expect(after.body.available).toBe(2);
  });

  it('refuses key material that is not key material', async () => {
    if (skip()) return;
    await http()
      .post('/api/v1/keys')
      .set(as(alicePhone))
      .send({
        registrationId: 1,
        identityKey: '<script>alert(1)</script>',
        signedPreKey: { keyId: 1, publicKey: seal('k'), signature: seal('s') },
        kyberPreKey: { keyId: 1, publicKey: seal('k'), signature: seal('s') },
      })
      .expect(400);
  });

  it('refuses a bundle with no Kyber prekey, rather than half a handshake', async () => {
    if (skip()) return;
    await http()
      .post('/api/v1/keys')
      .set(as(alicePhone))
      .send({
        registrationId: 4242,
        identityKey: seal('identity'),
        signedPreKey: { keyId: 1, publicKey: seal('k'), signature: seal('s') },
      })
      .expect(400);
  });

  it('says nothing about someone with no keys published', async () => {
    if (skip()) return;
    const res = await http().get(`/api/v1/keys/${carol.userId}`).set(as(alicePhone)).expect(200);
    expect(res.body.devices).toEqual([]);
  });

  // ------------------------------------------------------------- the message

  let cid = '';

  it('carries a message it cannot read', async () => {
    if (skip()) return;
    const sent = await http()
      .post('/api/v1/messages')
      .set(as(alicePhone))
      .send({
        toUserId: bobPhone.userId,
        clientMsgId: 'enc-1',
        envelopes: [
          { deviceId: bobPhone.deviceId, ciphertext: seal(SECRET), type: 3 },
          { deviceId: bobLaptop.deviceId, ciphertext: seal(SECRET), type: 3 },
          { deviceId: alicePhone.deviceId, ciphertext: seal(SECRET), type: 3 },
        ],
      })
      .expect(201);
    cid = sent.body.conversationId;
    expect(sent.body.message.type).toBe('ENCRYPTED');

    const rows = await sql('SELECT type, body, metadata::text AS meta FROM messages WHERE client_msg_id = $1', ['enc-1']);
    expect(rows).toHaveLength(1);
    expect(rows[0].type).toBe('ENCRYPTED');
    expect(rows[0].body).toBeNull();
    expect(rows[0].meta).toBeNull();

    // The ciphertext is there, and it is not the message.
    const envelopes = await sql(
      'SELECT device_id, ciphertext FROM message_envelopes WHERE message_id = (SELECT id FROM messages WHERE client_msg_id = $1)',
      ['enc-1'],
    );
    expect(envelopes).toHaveLength(3);
    for (const e of envelopes) expect(e.ciphertext).not.toContain('corner');
  });

  it('gives each device its own copy, and no one else theirs', async () => {
    if (skip()) return;
    const history = await http().get(`/api/v1/messages/conversations/${cid}`).set(as(bobPhone)).expect(200);
    const msg = history.body.find((m: any) => m.clientMsgId === 'enc-1');
    expect(msg.body).toBeNull();
    const devices = msg.envelopes.map((e: any) => e.deviceId).sort();
    // Bob sees his phone's and his laptop's copies; never Alice's.
    expect(devices).toEqual([bobPhone.deviceId, bobLaptop.deviceId].sort());
    expect(devices).not.toContain(alicePhone.deviceId);
    // Bob's phone needs to know which of Alice's devices sealed it.
    expect(msg.senderDeviceId).toBe(alicePhone.deviceId);
    const mine = msg.envelopes.find((e: any) => e.deviceId === bobPhone.deviceId);
    expect(Buffer.from(mine.ciphertext, 'base64').toString('utf-8')).toBe(SECRET);
    expect(mine.type).toBe(3);

    const alices = await http().get(`/api/v1/messages/conversations/${cid}`).set(as(alicePhone)).expect(200);
    expect(alices.body.find((m: any) => m.clientMsgId === 'enc-1').envelopes.map((e: any) => e.deviceId))
      .toEqual([alicePhone.deviceId]);
  });

  it('marks the chat encrypted for everyone in it', async () => {
    if (skip()) return;
    for (const who of [alicePhone, bobPhone, bobLaptop]) {
      const list = await http().get('/api/v1/messages/conversations').set(as(who)).expect(200);
      expect(list.body.find((c: any) => c.id === cid).encrypted).toBe(true);
    }
  });

  it('cannot find an encrypted message by searching the server', async () => {
    if (skip()) return;
    const found = await http().get('/api/v1/messages/search').query({ q: 'corner' }).set(as(alicePhone)).expect(200);
    expect(found.body).toHaveLength(0);
  });

  it('will not let the chat quietly go back to plain text', async () => {
    if (skip()) return;
    const res = await http()
      .post('/api/v1/messages')
      .set(as(alicePhone))
      .send({ conversationId: cid, body: 'just saying hello', clientMsgId: 'plain-1' })
      .expect(409);
    expect(res.body.code).toBe('E2EE_NOT_AVAILABLE');
    const rows = await sql('SELECT id FROM messages WHERE client_msg_id = $1', ['plain-1']);
    expect(rows).toHaveLength(0);
  });

  it('refuses a copy addressed to someone outside the conversation', async () => {
    if (skip()) return;
    await http()
      .post('/api/v1/messages')
      .set(as(alicePhone))
      .send({
        conversationId: cid,
        clientMsgId: 'enc-bad',
        envelopes: [
          { deviceId: bobPhone.deviceId, ciphertext: seal('x') },
          { deviceId: carol.deviceId, ciphertext: seal('x') },
        ],
      })
      .expect(400);
  });

  it('refuses to leave anyone in the chat without a copy', async () => {
    if (skip()) return;
    await http()
      .post('/api/v1/messages')
      .set(as(alicePhone))
      .send({
        conversationId: cid,
        clientMsgId: 'enc-partial',
        envelopes: [{ deviceId: alicePhone.deviceId, ciphertext: seal('only mine') }],
      })
      .expect(400);
  });

  it('says plainly when the other side cannot receive encrypted messages', async () => {
    if (skip()) return;
    const res = await http()
      .post('/api/v1/messages')
      .set(as(alicePhone))
      .send({
        toUserId: carol.userId,
        clientMsgId: 'enc-carol',
        envelopes: [{ deviceId: alicePhone.deviceId, ciphertext: seal('x') }],
      })
      .expect(409);
    expect(res.body.code).toBe('E2EE_NOT_AVAILABLE');
  });

  it('refuses to seal anything but text for now', async () => {
    if (skip()) return;
    await http()
      .post('/api/v1/messages')
      .set(as(alicePhone))
      .send({
        conversationId: cid,
        type: 'LOCATION',
        clientMsgId: 'enc-loc',
        location: { lat: -15.4, lng: 28.3 },
        envelopes: [{ deviceId: bobPhone.deviceId, ciphertext: seal('x') }],
      })
      .expect(400);
  });

  it('takes the ciphertext away when the sender deletes for everyone', async () => {
    if (skip()) return;
    const sent = await http()
      .post('/api/v1/messages')
      .set(as(alicePhone))
      .send({
        conversationId: cid,
        clientMsgId: 'enc-del',
        envelopes: [
          { deviceId: bobPhone.deviceId, ciphertext: seal('regret this') },
          { deviceId: bobLaptop.deviceId, ciphertext: seal('regret this') },
          { deviceId: alicePhone.deviceId, ciphertext: seal('regret this') },
        ],
      })
      .expect(201);
    await http()
      .delete(`/api/v1/messages/${sent.body.message.id}`)
      .query({ scope: 'everyone' })
      .set(as(alicePhone))
      .expect(200);
    const left = await sql('SELECT device_id FROM message_envelopes WHERE message_id = $1', [sent.body.message.id]);
    expect(left).toHaveLength(0);
  });

  it('forgets a device\'s keys when it is signed out', async () => {
    if (skip()) return;
    await http().delete(`/api/v1/devices/${bobLaptop.deviceId}`).set(as(bobPhone)).expect(200);
    const res = await http().get(`/api/v1/keys/${bobPhone.userId}`).set(as(alicePhone)).expect(200);
    expect(res.body.devices.map((d: any) => d.deviceId)).not.toContain(bobLaptop.deviceId);
    const rows = await sql('SELECT device_id FROM device_identity_keys WHERE device_id = $1', [bobLaptop.deviceId]);
    expect(rows).toHaveLength(0);
  });
});
