/**
 * End-to-end: message content is unreadable in the database and on disk, and
 * everything that used to read it still works.
 *
 * This suite sets MESSAGE_ENCRYPTION_KEY before the app starts, so it exercises
 * the encrypted path the way production runs.
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
import { resetEncryptionKeyCache } from '../../src/common/crypto/field-cipher';

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

type User = { userId: string; accessToken: string };

const SECRET_TEXT = 'The password for the safe is 4821';
const MEDIA_DIR = join(__dirname, '../../.tmp-encrypted-media');

describe('Encryption at rest', () => {
  let app: INestApplication;
  let available = false;
  let alice: User;
  let bob: User;
  let cid = '';
  const http = () => request(app.getHttpServer());
  const as = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });

  async function register(phone: string, name: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: phone });
    const v = await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: `key-${phone}`,
      platform: 'ANDROID',
      appVersion: '0.4.0',
    });
    await http().patch('/api/v1/me').set({ Authorization: `Bearer ${v.body.accessToken}` }).send({ displayName: name });
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
    process.env.MESSAGE_ENCRYPTION_KEY = Buffer.alloc(32, 42).toString('base64');
    process.env.MEDIA_UPLOAD_DIR = MEDIA_DIR;
    resetEncryptionKeyCache();
    await resetDatabase();
    app = await createTestApp();
    alice = await register('+260978800001', 'Alice');
    bob = await register('+260978800002', 'Bob');
    const first = await http().post('/api/v1/messages').set(as(alice))
      .send({ toUserId: bob.userId, body: SECRET_TEXT, clientMsgId: 'e1' });
    cid = first.body.conversationId;
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
    delete process.env.MESSAGE_ENCRYPTION_KEY;
    resetEncryptionKeyCache();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  it('stores nothing readable in the database', async () => {
    if (skip()) return;
    const rows = await sql('SELECT body FROM messages WHERE client_msg_id = $1', ['e1']);
    expect(rows).toHaveLength(1);
    expect(rows[0].body).not.toContain('4821');
    expect(rows[0].body).not.toContain('password');
    expect(rows[0].body.startsWith('v1.')).toBe(true);

    // And a search of the whole column finds nothing — the point of the exercise.
    const scan = await sql('SELECT count(*)::int AS n FROM messages WHERE body ILIKE $1', ['%4821%']);
    expect(scan[0].n).toBe(0);
  });

  it('gives the message back to the people in the chat', async () => {
    if (skip()) return;
    const history = await http().get(`/api/v1/messages/conversations/${cid}`).set(as(bob)).expect(200);
    expect(history.body.find((m: any) => m.clientMsgId === 'e1').body).toBe(SECRET_TEXT);
  });

  it('hides what is inside a contact card too', async () => {
    if (skip()) return;
    await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: cid,
      type: 'CONTACT',
      clientMsgId: 'e2',
      contact: { name: 'Mwila Banda', phones: ['+260971234567'] },
    }).expect(201);

    const rows = await sql('SELECT metadata::text AS meta FROM messages WHERE client_msg_id = $1', ['e2']);
    expect(rows[0].meta).not.toContain('260971234567');
    expect(rows[0].meta).not.toContain('Mwila');

    const history = await http().get(`/api/v1/messages/conversations/${cid}`).set(as(bob)).expect(200);
    const card = history.body.find((m: any) => m.clientMsgId === 'e2');
    expect(card.metadata.contact).toMatchObject({ name: 'Mwila Banda', phones: ['+260971234567'] });
  });

  it('still finds the message when the person searches for it', async () => {
    if (skip()) return;
    const found = await http().get('/api/v1/messages/search').query({ q: '4821' }).set(as(alice)).expect(200);
    expect(found.body.map((m: any) => m.clientMsgId)).toContain('e1');
    const none = await http().get('/api/v1/messages/search').query({ q: 'kalambo' }).set(as(alice)).expect(200);
    expect(none.body).toHaveLength(0);
  });

  it('keeps the mention badge working, now that it has its own table', async () => {
    if (skip()) return;
    const group = await http().post('/api/v1/messages/groups').set(as(alice))
      .send({ title: 'Work', memberIds: [bob.userId] }).expect(201);
    await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: group.body.id,
      body: '@Bob can you check this',
      clientMsgId: 'e3',
      mentions: [bob.userId],
    }).expect(201);

    const rows = await sql('SELECT user_id FROM message_mentions WHERE conversation_id = $1', [group.body.id]);
    expect(rows.map((r: any) => r.user_id)).toEqual([bob.userId]);

    const list = await http().get('/api/v1/messages/conversations').set(as(bob)).expect(200);
    expect(list.body.find((c: any) => c.id === group.body.id).mentionedUnread).toBe(true);
  });

  it('stores files unreadable, and hands them back whole', async () => {
    if (skip()) return;
    const contents = Buffer.from('%PDF-1.4 the quarterly figures are 12,500 kwacha');
    const upload = await http().post('/api/v1/messages/media').set(as(alice))
      .field('kind', 'FILE')
      .field('fileName', 'figures.pdf')
      .attach('file', contents, { filename: 'figures.pdf', contentType: 'application/pdf' })
      .expect(201);

    const stored = await sql('SELECT file_name FROM media_objects WHERE id = $1', [upload.body.id]);
    const onDisk = readFileSync(join(MEDIA_DIR, stored[0].file_name));
    expect(onDisk.includes('12,500 kwacha')).toBe(false);
    expect(onDisk.subarray(0, 5).toString()).toBe('VIRO1');

    await http().post('/api/v1/messages').set(as(alice))
      .send({ conversationId: cid, type: 'FILE', mediaId: upload.body.id, clientMsgId: 'e4' }).expect(201);
    const download = await http().get(`/api/v1/messages/media/${upload.body.id}`).set(as(bob)).expect(200);
    expect(Buffer.from(download.body).equals(contents)).toBe(true);
  });

  it('reads rows written before encryption was switched on', async () => {
    if (skip()) return;
    // A message as an older release would have stored it.
    await sql(
      `INSERT INTO messages (id, conversation_id, sender_user_id, type, body, created_at, updated_at, client_msg_id)
       VALUES (gen_random_uuid(), $1, $2, 'TEXT', $3, NOW(), NOW(), 'legacy-1')`,
      [cid, alice.userId, 'written before we encrypted anything'],
    );
    const history = await http().get(`/api/v1/messages/conversations/${cid}`).set(as(bob)).expect(200);
    expect(history.body.find((m: any) => m.clientMsgId === 'legacy-1').body)
      .toBe('written before we encrypted anything');
  });
});
