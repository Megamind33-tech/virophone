/**
 * End-to-end: document attachments, shared contact cards, and the inbox tools
 * (archive, pin, mark as unread).
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

jest.setTimeout(30_000);

type User = { userId: string; accessToken: string };

describe('Documents, contact cards and inbox tools', () => {
  let app: INestApplication;
  let available = false;
  let alice: User;
  let bob: User;
  let cid = '';
  const http = () => request(app.getHttpServer());
  const as = (u: User) => ({ Authorization: `Bearer ${u.accessToken}`, 'X-Timezone': 'Africa/Lusaka' });

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

  const conversations = async (u: User) =>
    (await http().get('/api/v1/messages/conversations').set(as(u))).body as Record<string, any>[];

  /** This conversation as that person sees it — the inbox flags are per person. */
  const inbox = async (u: User) => {
    const found = (await conversations(u)).find((c) => c.id === cid);
    if (!found) throw new Error("conversation missing from the inbox");
    return found;
  };

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
    alice = await register('+260978300001', 'Alice');
    bob = await register('+260978300002', 'Bob');
    const first = await http().post('/api/v1/messages').set(as(alice)).send({ toUserId: bob.userId, body: 'Hi Bob' });
    cid = first.body.conversationId;
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  // ---------------------------------------------------------------- documents

  it('sends a document and gives it back under its own name', async () => {
    if (skip()) return;
    const upload = await http().post('/api/v1/messages/media').set(as(alice))
      .field('kind', 'FILE')
      .field('fileName', 'Quarter 3 invoice.pdf')
      .attach('file', Buffer.from('%PDF-1.4 fake'), { filename: 'upload.pdf', contentType: 'application/pdf' })
      .expect(201);
    expect(upload.body).toMatchObject({ kind: 'FILE', mime: 'application/pdf', originalName: 'Quarter 3 invoice.pdf' });

    const sent = await http().post('/api/v1/messages').set(as(alice))
      .send({ conversationId: cid, type: 'FILE', mediaId: upload.body.id, clientMsgId: 'f1' })
      .expect(201);
    expect(sent.body.message.media).toMatchObject({ originalName: 'Quarter 3 invoice.pdf', sizeBytes: 13 });
    expect(sent.body.message.metadata.file).toMatchObject({ name: 'Quarter 3 invoice.pdf', mime: 'application/pdf' });

    // Bob can fetch it, and it arrives named.
    const download = await http().get(`/api/v1/messages/media/${upload.body.id}`).set(as(bob)).expect(200);
    expect(download.headers['content-disposition']).toContain('Quarter 3 invoice.pdf');
    expect(download.headers['content-type']).toContain('application/pdf');
  });

  it('refuses installable files and strips any path from the name', async () => {
    if (skip()) return;
    await http().post('/api/v1/messages/media').set(as(alice))
      .field('kind', 'FILE')
      .attach('file', Buffer.from('MZ fake'), { filename: 'app.apk', contentType: 'application/vnd.android.package-archive' })
      .expect(400);

    const sneaky = await http().post('/api/v1/messages/media').set(as(alice))
      .field('kind', 'FILE')
      .field('fileName', '../../etc/passwd.txt')
      .attach('file', Buffer.from('notes'), { filename: 'notes.txt', contentType: 'text/plain' })
      .expect(201);
    expect(sneaky.body.originalName).toBe('passwd.txt');
  });

  it('needs a file to send a document message', async () => {
    if (skip()) return;
    await http().post('/api/v1/messages').set(as(alice))
      .send({ conversationId: cid, type: 'FILE', clientMsgId: 'f2' })
      .expect(400);
  });

  // ------------------------------------------------------------ contact cards

  it('shares a contact card, and refuses an empty one', async () => {
    if (skip()) return;
    const sent = await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: cid,
      type: 'CONTACT',
      clientMsgId: 'c1',
      contact: { name: 'Mwila Banda', phones: ['+260978300009'], viroId: '@mwila' },
    }).expect(201);
    expect(sent.body.message.type).toBe('CONTACT');
    expect(sent.body.message.metadata.contact).toMatchObject({
      name: 'Mwila Banda',
      phones: ['+260978300009'],
      viroId: '@mwila',
    });

    // Bob sees the card in his history.
    const history = await http().get(`/api/v1/messages/conversations/${cid}`).set(as(bob)).expect(200);
    const card = history.body.find((m: any) => m.clientMsgId === 'c1' || m.type === 'CONTACT');
    expect(card.metadata.contact.name).toBe('Mwila Banda');

    await http().post('/api/v1/messages').set(as(alice))
      .send({ conversationId: cid, type: 'CONTACT', clientMsgId: 'c2', contact: { name: 'Nobody' } })
      .expect(400);
    await http().post('/api/v1/messages').set(as(alice))
      .send({ conversationId: cid, type: 'CONTACT', clientMsgId: 'c3', contact: { name: '', phones: ['+260978300009'] } })
      .expect(400);
  });

  // ------------------------------------------------------------- inbox tools

  it('archives and unarchives a chat, for me only', async () => {
    if (skip()) return;
    await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(alice)).send({ archived: true }).expect(200);
    expect((await inbox(alice)).archived).toBe(true);
    // Bob's inbox is untouched.
    expect((await inbox(bob)).archived).toBe(false);

    await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(alice)).send({ archived: false }).expect(200);
    expect((await inbox(alice)).archived).toBe(false);
  });

  it('pins a chat, and keeps the moment it was pinned', async () => {
    if (skip()) return;
    const pinned = await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(alice)).send({ pinned: true }).expect(200);
    expect(pinned.body.pinnedAt).toBeTruthy();
    const at = pinned.body.pinnedAt;

    // Pinning again doesn't move it up the list.
    const again = await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(alice)).send({ pinned: true }).expect(200);
    expect(again.body.pinnedAt).toBe(at);
    expect((await inbox(bob)).pinnedAt).toBeNull();

    await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(alice)).send({ pinned: false }).expect(200);
    expect((await inbox(alice)).pinnedAt).toBeNull();
  });

  it('marks a chat unread until it is opened again', async () => {
    if (skip()) return;
    await http().post(`/api/v1/messages/conversations/${cid}/read`).set(as(bob));
    await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(bob)).send({ markUnread: true }).expect(200);
    expect((await inbox(bob)).unreadMarked).toBe(true);
    // Alice sees nothing of it.
    expect((await inbox(alice)).unreadMarked).toBe(false);

    // Opening the chat clears it.
    await http().post(`/api/v1/messages/conversations/${cid}/read`).set(as(bob)).expect(201);
    expect((await inbox(bob)).unreadMarked).toBe(false);
  });

  it('keeps archive, pin and mark-unread across a sync', async () => {
    if (skip()) return;
    await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(alice))
      .send({ archived: true, pinned: true, markUnread: true }).expect(200);
    const sync = await http().get('/api/v1/messages/sync').query({ since: new Date(Date.now() - 300_000).toISOString() }).set(as(alice)).expect(200);
    const conv = sync.body.conversations.find((c: any) => c.id === cid);
    expect(conv).toMatchObject({ archived: true, unreadMarked: true });
    expect(conv.pinnedAt).toBeTruthy();
  });
});
