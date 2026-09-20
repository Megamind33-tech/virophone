/**
 * End-to-end: @mentions in a group (who may be mentioned, the badge, and the
 * push that cuts through mute) and group invite links.
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

describe('Group mentions and invite links', () => {
  let app: INestApplication;
  let available = false;
  let alice: User; // group admin
  let bob: User;
  let carol: User;
  let dan: User; // outside the group, holds a link
  let groupId = '';
  const pushes: { userId: string; title?: string; data?: Record<string, string> }[] = [];
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

  const conv = async (u: User, id: string) => {
    const list = (await http().get('/api/v1/messages/conversations').set(as(u))).body as Record<string, any>[];
    const found = list.find((c) => c.id === id);
    if (!found) throw new Error('conversation not in the inbox');
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
    const push = app.get(PushService);
    jest.spyOn(push, 'sendToUser').mockImplementation(async (userId, payload) => {
      pushes.push({ userId, title: payload.title, data: payload.data });
    });
    alice = await register('+260978500001', 'Alice');
    bob = await register('+260978500002', 'Bob');
    carol = await register('+260978500003', 'Carol');
    dan = await register('+260978500004', 'Dan');
    const group = await http().post('/api/v1/messages/groups').set(as(alice))
      .send({ title: 'Saturday football', memberIds: [bob.userId, carol.userId] })
      .expect(201);
    groupId = group.body.id;
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  // ------------------------------------------------------------------ mentions

  it('names someone with @, and tells their phone to ring through mute', async () => {
    if (skip()) return;
    pushes.length = 0;
    const sent = await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: groupId,
      body: '@Bob are you playing?',
      clientMsgId: 'm1',
      mentions: [bob.userId],
    }).expect(201);
    expect(sent.body.message.metadata.mentions).toEqual([bob.userId]);

    const bobPush = pushes.find((p) => p.userId === bob.userId);
    expect(bobPush?.data?.mention).toBe('1');
    expect(bobPush?.title).toBe('Alice mentioned you in Saturday football');
    // Carol wasn't named: an ordinary group message for her.
    const carolPush = pushes.find((p) => p.userId === carol.userId);
    expect(carolPush?.data?.mention).toBeUndefined();
    expect(carolPush?.title).toBe('Saturday football');
  });

  it('shows the mention badge until the chat is read', async () => {
    if (skip()) return;
    expect((await conv(bob, groupId)).mentionedUnread).toBe(true);
    expect((await conv(carol, groupId)).mentionedUnread).toBe(false);

    await http().post(`/api/v1/messages/conversations/${groupId}/read`).set(as(bob)).expect(201);
    expect((await conv(bob, groupId)).mentionedUnread).toBe(false);
  });

  it('keeps only people who are actually in the group, and never the sender', async () => {
    if (skip()) return;
    const sent = await http().post('/api/v1/messages').set(as(alice)).send({
      conversationId: groupId,
      body: 'testing',
      clientMsgId: 'm2',
      mentions: [bob.userId, dan.userId, alice.userId, 'not-a-user'],
    }).expect(201);
    expect(sent.body.message.metadata.mentions).toEqual([bob.userId]);
  });

  // ------------------------------------------------------------- invite links

  it('gives admins a link, and refuses everyone else', async () => {
    if (skip()) return;
    await http().get(`/api/v1/messages/conversations/${groupId}/invite`).set(as(bob)).expect(403);

    const invite = await http().get(`/api/v1/messages/conversations/${groupId}/invite`).set(as(alice)).expect(200);
    expect(invite.body.code).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(invite.body.url).toContain(`/api/v1/invite/g/${invite.body.code}`);

    // Asking again gives the same link rather than churning it.
    const again = await http().get(`/api/v1/messages/conversations/${groupId}/invite`).set(as(alice)).expect(200);
    expect(again.body.code).toBe(invite.body.code);
  });

  it('shows what a link leads to, then joins', async () => {
    if (skip()) return;
    const { body: invite } = await http().get(`/api/v1/messages/conversations/${groupId}/invite`).set(as(alice)).expect(200);

    const preview = await http().get(`/api/v1/messages/groups/invite/${invite.code}`).set(as(dan)).expect(200);
    expect(preview.body).toMatchObject({ title: 'Saturday football', memberCount: 3, alreadyMember: false });

    const joined = await http().post(`/api/v1/messages/groups/invite/${invite.code}/join`).set(as(dan)).expect(201);
    expect(joined.body.id).toBe(groupId);
    expect(joined.body.participants).toContain(dan.userId);

    // The group sees that he joined.
    const history = await http().get(`/api/v1/messages/conversations/${groupId}`).set(as(alice)).expect(200);
    expect(history.body.some((m: any) => m.type === 'SYSTEM' && m.body?.includes('joined using the group link'))).toBe(true);

    // Joining twice is harmless.
    await http().post(`/api/v1/messages/groups/invite/${invite.code}/join`).set(as(dan)).expect(201);
    expect((await conv(alice, groupId)).participants).toHaveLength(4);
  });

  it('retires the old link when it is reset, and stops it entirely when revoked', async () => {
    if (skip()) return;
    const { body: first } = await http().get(`/api/v1/messages/conversations/${groupId}/invite`).set(as(alice)).expect(200);
    const { body: second } = await http().post(`/api/v1/messages/conversations/${groupId}/invite/reset`).set(as(alice)).expect(201);
    expect(second.code).not.toBe(first.code);
    await http().get(`/api/v1/messages/groups/invite/${first.code}`).set(as(dan)).expect(404);
    await http().get(`/api/v1/messages/groups/invite/${second.code}`).set(as(dan)).expect(200);

    await http().delete(`/api/v1/messages/conversations/${groupId}/invite`).set(as(alice)).expect(200);
    await http().get(`/api/v1/messages/groups/invite/${second.code}`).set(as(dan)).expect(404);
    await http().post(`/api/v1/messages/groups/invite/${second.code}/join`).set(as(dan)).expect(404);
  });

  it('serves a group link page that gives nothing away', async () => {
    if (skip()) return;
    const { body: invite } = await http().post(`/api/v1/messages/conversations/${groupId}/invite/reset`).set(as(alice)).expect(201);
    const page = await http().get(`/api/v1/invite/g/${invite.code}`).expect(200);
    expect(page.text).toContain(`viro://g/${invite.code}`);
    expect(page.text).not.toContain('Saturday football');
    await http().get('/api/v1/invite/g/!!').expect(404);
  });

  it('keeps out someone the group owner blocked', async () => {
    if (skip()) return;
    const { body: invite } = await http().get(`/api/v1/messages/conversations/${groupId}/invite`).set(as(alice)).expect(200);
    await http().post('/api/v1/blocks').set(as(alice)).send({ blockedUserId: carol.userId });
    // Carol is already in; a blocked outsider is the case that matters.
    const eve = await register('+260978500005', 'Eve');
    await http().post('/api/v1/blocks').set(as(alice)).send({ blockedUserId: eve.userId });
    await http().post(`/api/v1/messages/groups/invite/${invite.code}/join`).set(as(eve)).expect(403);
  });
});
