/**
 * End-to-end: messaging v2 (edit, delete, reactions, replies, voice, view
 * once, send later, disappearing, private sessions, clear/reset/erase,
 * typing, receipts, sync) and the relationship system (targets, dates,
 * commitments, Loops with reciprocal reveal, timeline, privacy).
 *
 * Needs PostgreSQL. Redis may be real, or mocked with REDIS_MOCK=1 (the
 * in-memory mock is enough: a single API instance delivers locally).
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
import { AddressInfo } from 'net';
import { WebSocket } from 'ws';
import { createTestApp } from './test-app';
import { MessagesService } from '../../src/messages/messages.service';

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

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

jest.setTimeout(30_000);

type User = { userId: string; deviceId: string; accessToken: string };

describe('Messaging v2 and relationships end-to-end', () => {
  let app: INestApplication;
  let port = 0;
  let available = false;
  const sockets: WebSocket[] = [];
  let alice: User;
  let bob: User;
  let carol: User;
  const inbox: Record<string, Record<string, unknown>[]> = {};

  const http = () => request(app.getHttpServer());
  const as = (u: User) => ({ Authorization: `Bearer ${u.accessToken}`, 'X-Timezone': 'Africa/Lusaka' });

  async function register(phone: string, key: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: phone });
    const v = await http().post('/api/v1/auth/otp/verify').send({
      challengeId: otp.body.challengeId,
      code: process.env.TEST_OTP_CODE || '123456',
      devicePublicKey: key,
      platform: 'ANDROID',
      appVersion: '0.4.0',
    });
    await http().patch('/api/v1/me').set('Authorization', `Bearer ${v.body.accessToken}`).send({ displayName: key });
    return v.body;
  }

  async function connect(u: User, name: string) {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${u.accessToken}`);
    sockets.push(ws);
    inbox[name] = [];
    ws.on('message', (d) => {
      try {
        inbox[name].push(JSON.parse(d.toString()));
      } catch {
        /* ignore */
      }
    });
    await new Promise<void>((res, rej) => {
      ws.once('open', () => res());
      ws.once('error', rej);
    });
    await delay(200);
    return ws;
  }

  async function waitFor(name: string, pred: (f: Record<string, unknown>) => boolean, ms = 4000) {
    const start = Date.now();
    while (Date.now() - start < ms) {
      const hit = inbox[name].find(pred);
      if (hit) return hit;
      await delay(50);
    }
    throw new Error(`${name} never received the expected frame. Got: ${JSON.stringify(inbox[name].map((f) => f.type))}`);
  }

  const send = async (from: User, body: Record<string, unknown>) => {
    const r = await http().post('/api/v1/messages').set(as(from)).send(body);
    if (![200, 201].includes(r.status)) throw new Error(`send failed ${r.status} ${JSON.stringify(r.body)}`);
    return r.body as { conversationId: string; message: Record<string, any> };
  };
  const history = async (u: User, cid: string) =>
    (await http().get(`/api/v1/messages/conversations/${cid}`).set(as(u))).body as Record<string, any>[];

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
    await app.listen(0);
    port = (app.getHttpServer().address() as AddressInfo).port;
    alice = await register('+260978100001', 'Alice');
    bob = await register('+260978100002', 'Bob');
    carol = await register('+260978100003', 'Carol');
    await connect(alice, 'alice');
    await connect(bob, 'bob');
  }, 120_000);

  afterAll(async () => {
    for (const ws of sockets) if (ws.readyState === WebSocket.OPEN) ws.close();
    await delay(200);
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  let cid = '';

  it('delivers in realtime, syncs, and moves ticks', async () => {
    if (skip()) return;
    const s = await send(alice, { toUserId: bob.userId, body: 'Hello Bob', clientMsgId: 'm1' });
    cid = s.conversationId;
    const frame = await waitFor('bob', (f) => f.type === 'message.new' && (f.message as any)?.body === 'Hello Bob');
    expect((frame.payload as any).conversationId).toBe(cid);

    // Delivered the moment Bob's socket had it.
    await waitFor('alice', (f) => f.type === 'message.receipt' && !!f.lastDeliveredAt);

    // A missed frame is harmless: sync from before the send returns it.
    const sync = await http().get('/api/v1/messages/sync').query({ since: new Date(Date.now() - 60_000).toISOString() }).set(as(bob));
    expect(sync.body.messages.map((m: any) => m.body)).toContain('Hello Bob');
    expect(sync.body.conversations[0].id).toBe(cid);

    await http().post(`/api/v1/messages/conversations/${cid}/read`).set(as(bob)).expect(201);
    await waitFor('alice', (f) => f.type === 'message.receipt' && !!f.lastReadAt);
    const summary = await http().get(`/api/v1/messages/conversations/${cid}/summary`).set(as(alice));
    expect(summary.body.peerLastReadAt).toBeTruthy();
  });

  it('edits, reacts, replies, and deletes', async () => {
    if (skip()) return;
    const s = await send(alice, { conversationId: cid, body: 'Meet at 5' });
    const id = s.message.id;
    const edited = await http().patch(`/api/v1/messages/${id}`).set(as(alice)).send({ body: 'Meet at 6' });
    expect(edited.body.body).toBe('Meet at 6');
    expect(edited.body.editedAt).toBeTruthy();
    await waitFor('bob', (f) => f.type === 'message.updated' && (f.message as any)?.body === 'Meet at 6');

    // Only the sender edits.
    await http().patch(`/api/v1/messages/${id}`).set(as(bob)).send({ body: 'hijack' }).expect(403);

    const reacted = await http().put(`/api/v1/messages/${id}/reaction`).set(as(bob)).send({ emoji: '👍' });
    expect(reacted.body.reactions).toEqual([{ userId: bob.userId, emoji: '👍' }]);

    const reply = await send(bob, { conversationId: cid, body: 'Works for me', replyToId: id });
    expect(reply.message.replyTo.body).toBe('Meet at 6');

    await http().delete(`/api/v1/messages/${id}`).query({ scope: 'everyone' }).set(as(alice)).expect(200);
    const afterDelete = (await history(bob, cid)).find((m) => m.id === id)!;
    expect(afterDelete.body).toBeNull();
    expect(afterDelete.deletedAt).toBeTruthy();
    expect(afterDelete.reactions).toEqual([]);

    // Delete for me hides it from Bob only.
    await http().delete(`/api/v1/messages/${reply.message.id}`).query({ scope: 'me' }).set(as(bob)).expect(200);
    expect((await history(bob, cid)).some((m) => m.id === reply.message.id)).toBe(false);
    expect((await history(alice, cid)).some((m) => m.id === reply.message.id)).toBe(true);
  });

  it('sends voice notes privately, and view-once is consumed', async () => {
    if (skip()) return;
    const up = await http()
      .post('/api/v1/messages/media')
      .set(as(alice))
      .field('durationMs', '2400')
      .field('waveform', 'AAECAwQ=')
      .attach('file', Buffer.from('fake-m4a-bytes'), { filename: 'v.m4a', contentType: 'audio/mp4' });
    expect(up.status).toBe(201);
    const voice = await send(alice, { conversationId: cid, type: 'VOICE', mediaId: up.body.id });
    expect(voice.message.media.durationMs).toBe(2400);
    await http().get(`/api/v1/messages/media/${up.body.id}`).set(as(bob)).expect(200);
    await http().get(`/api/v1/messages/media/${up.body.id}`).set(as(carol)).expect(404);

    const up2 = await http()
      .post('/api/v1/messages/media')
      .set(as(alice))
      .attach('file', Buffer.from('once'), { filename: 'o.m4a', contentType: 'audio/mp4' });
    const once = await send(alice, { conversationId: cid, type: 'VOICE', mediaId: up2.body.id, viewOnce: true });
    await http().post(`/api/v1/messages/${once.message.id}/viewed`).set(as(bob)).expect(201);
    await http().get(`/api/v1/messages/media/${up2.body.id}`).set(as(bob)).expect(404);
    const seenBySender = (await history(alice, cid)).find((m) => m.id === once.message.id)!;
    expect(seenBySender.viewed).toBe(true);
  });

  it('holds send-later messages until due', async () => {
    if (skip()) return;
    const later = await send(alice, {
      conversationId: cid,
      body: 'Happy birthday!',
      deliverAt: new Date(Date.now() + 3600_000).toISOString(),
    });
    expect((await history(bob, cid)).some((m) => m.id === later.message.id)).toBe(false);
    expect((await history(alice, cid)).find((m) => m.id === later.message.id)!.deliverAt).toBeTruthy();
    await sql(`UPDATE messages SET deliver_at = NOW() - interval '1 second' WHERE id = $1`, [later.message.id]);
    await app.get(MessagesService).sweep();
    await waitFor('bob', (f) => f.type === 'message.new' && (f.message as any)?.id === later.message.id);
    expect((await history(bob, cid)).some((m) => m.id === later.message.id)).toBe(true);
  });

  it('disappearing messages expire for both', async () => {
    if (skip()) return;
    const set = await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(bob)).send({ disappearingSeconds: 86400 });
    expect(set.body.disappearingSeconds).toBe(86400);
    const temp = await send(alice, { conversationId: cid, body: 'secret' });
    expect(temp.message.expiresAt).toBeTruthy();
    await sql(`UPDATE messages SET expires_at = NOW() - interval '1 second' WHERE id = $1`, [temp.message.id]);
    await app.get(MessagesService).sweep();
    await waitFor('bob', (f) => f.type === 'message.removed' && (f.messageIds as string[])?.includes(temp.message.id));
    expect((await history(bob, cid)).some((m) => m.id === temp.message.id)).toBe(false);
    await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(bob)).send({ disappearingSeconds: null });
  });

  it('private sessions delete themselves at the chosen time', async () => {
    if (skip()) return;
    const p = await http().post('/api/v1/messages/private').set(as(alice)).send({ toUserId: bob.userId, durationSeconds: 3600 });
    expect(p.body.kind).toBe('PRIVATE');
    const pid = p.body.id;
    await send(bob, { conversationId: pid, body: 'just between us' });
    await sql(`UPDATE conversations SET expires_at = NOW() - interval '1 second' WHERE id = $1`, [pid]);
    await app.get(MessagesService).sweep();
    await waitFor('alice', (f) => f.type === 'conversation.erased' && f.conversationId === pid);
    const list = await http().get('/api/v1/messages/conversations').set(as(alice));
    expect(list.body.some((c: any) => c.id === pid)).toBe(false);
    expect((await sql('SELECT COUNT(*)::int AS n FROM messages WHERE conversation_id = $1', [pid]))[0].n).toBe(0);
  });

  it('relays typing only to the other person', async () => {
    if (skip()) return;
    const ws = sockets[0];
    ws.send(JSON.stringify({ event: 'chat', data: { type: 'chat.typing', conversationId: cid, state: 'recording' } }));
    const f = await waitFor('bob', (x) => x.type === 'chat.typing');
    expect(f.state).toBe('recording');
    expect(f.userId).toBe(alice.userId);
  });

  it('hides, clears for me, resets for both', async () => {
    if (skip()) return;
    const hidden = await http().patch(`/api/v1/messages/conversations/${cid}/settings`).set(as(bob)).send({ hidden: true });
    expect(hidden.body.hidden).toBe(true);
    await send(alice, { conversationId: cid, body: 'you there?' });
    const bobList = await http().get('/api/v1/messages/conversations').set(as(bob));
    expect(bobList.body.find((c: any) => c.id === cid).hidden).toBe(false);

    await http().post(`/api/v1/messages/conversations/${cid}/clear`).set(as(bob)).expect(201);
    expect(await history(bob, cid)).toHaveLength(0);
    expect((await history(alice, cid)).length).toBeGreaterThan(0);

    await http().post(`/api/v1/messages/conversations/${cid}/reset`).set(as(alice)).expect(201);
    await waitFor('bob', (f) => f.type === 'conversation.reset' && f.conversationId === cid);
    const after = await history(alice, cid);
    expect(after.every((m) => m.type === 'SYSTEM')).toBe(true);
  });

  let relId = '';

  it('tracks a target from real contact, in the right words', async () => {
    if (skip()) return;
    const rel = await http().put('/api/v1/relationships').set(as(alice)).send({
      subjectUserId: bob.userId, subjectPhone: '+260978100002', displayName: 'Mum',
      relationshipType: 'PARENT', targetCadence: 'DAILY',
    });
    expect(rel.status).toBe(200);
    relId = rel.body.id;
    expect(rel.body.category).toBe('PERSONAL');

    // Nothing from Alice since the reset: today's check-in is still due.
    let ov = await http().get('/api/v1/relationships/overview').set(as(alice));
    expect(ov.body.relationships.find((r: any) => r.id === relId).health.code).toBe('DUE_TODAY');

    // Once she messages, today's check-in is met...
    await send(alice, { conversationId: cid, body: 'How are you today?' });
    ov = await http().get('/api/v1/relationships/overview').set(as(alice));
    let mine = ov.body.relationships.find((r: any) => r.id === relId);
    expect(mine.health.code).toBe('ON_TRACK');

    // ...but not if her messages are all from yesterday.
    await sql(`UPDATE messages SET created_at = NOW() - interval '2 days' WHERE sender_user_id = $1`, [alice.userId]);
    ov = await http().get('/api/v1/relationships/overview').set(as(alice));
    mine = ov.body.relationships.find((r: any) => r.id === relId);
    expect(mine.health.code).toBe('DUE_TODAY');
    expect(mine.health.text).toBe("You haven't spoken to Mum today. Check on them. ❤️");
    expect(ov.body.attention.some((a: any) => a.relationshipId === relId)).toBe(true);

    // A logged check-in (a normal phone call) counts.
    await http().post(`/api/v1/relationships/${relId}/checkins`).set(as(alice)).send({ note: 'Phoned' }).expect(201);
    ov = await http().get('/api/v1/relationships/overview').set(as(alice));
    expect(ov.body.relationships.find((r: any) => r.id === relId).health.code).toBe('ON_TRACK');
  });

  it('keeps classifications private', async () => {
    if (skip()) return;
    const bobView = await http().get('/api/v1/relationships/overview').set(as(bob));
    expect(bobView.body.relationships).toHaveLength(0);
    await http().post(`/api/v1/relationships/${relId}/dates`).set(as(bob)).send({ kind: 'BIRTHDAY', month: 1, day: 1 }).expect(404);
  });

  it('remembers important dates and commitments', async () => {
    if (skip()) return;
    const tomorrow = new Date(Date.now() + 86_400_000);
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Lusaka', month: 'numeric', day: 'numeric' }).formatToParts(tomorrow);
    const month = +parts.find((p) => p.type === 'month')!.value;
    const day = +parts.find((p) => p.type === 'day')!.value;
    await http().post(`/api/v1/relationships/${relId}/dates`).set(as(alice)).send({ kind: 'BIRTHDAY', month, day }).expect(201);
    const ov = await http().get('/api/v1/relationships/overview').set(as(alice));
    const r = ov.body.relationships.find((x: any) => x.id === relId);
    expect(r.nextDate.daysAway).toBe(1);
    expect(r.nextDate.sentence).toBe("Mum's birthday is tomorrow. 🎉");
    expect(ov.body.comingUp.some((c: any) => c.label === 'Tomorrow')).toBe(true);

    const c = await http().post('/api/v1/relationships/commitments').set(as(alice)).send({
      text: 'call back', kind: 'CALL', dueAt: new Date(Date.now() - 60_000).toISOString(), relationshipId: relId,
    });
    expect(c.status).toBe(201);
    const nudges = await http().get('/api/v1/relationships/nudges').set(as(alice));
    const high = nudges.body.nudges.find((n: any) => n.priority === 'HIGH' && n.kind === 'COMMITMENT');
    expect(high.body).toMatch(/You promised to call Mum/);
    expect(nudges.body.brief.lines.length).toBeGreaterThan(0);

    await http().patch(`/api/v1/relationships/commitments/${c.body.id}`).set(as(alice)).send({ status: 'DONE' }).expect(200);
    const tl = await http().get(`/api/v1/relationships/${relId}/timeline`).set(as(alice));
    expect(tl.body.items.some((i: any) => i.kind === 'COMMITMENT_DONE')).toBe(true);
    expect(tl.body.items.some((i: any) => i.kind === 'CHECKIN')).toBe(true);
  });

  it('runs a reciprocal Loop: private until both answer', async () => {
    if (skip()) return;
    const loop = await http().post('/api/v1/loops').set(as(alice)).send({
      toUserId: bob.userId, title: 'Evening Check-In', prompt: 'What was the best part of your day?',
      frequency: 'DAILY', timeOfDay: '00:00', timezone: 'Africa/Lusaka', reciprocal: true,
    });
    expect(loop.status).toBe(201);
    const lid = loop.body.id;

    const bobAnswer = await http().post(`/api/v1/loops/${lid}/answer`).set(as(bob)).send({ kind: 'TEXT', text: 'Lunch with you' });
    expect(bobAnswer.body.waitingFor).toEqual([alice.userId]);

    // Alice sees that Bob answered, but not what he said.
    let aliceState = (await http().get('/api/v1/loops').query({ conversationId: loop.body.conversationId }).set(as(alice))).body[0];
    expect(aliceState.answeredBy).toContain(bob.userId);
    expect(aliceState.answers).toHaveLength(0);
    const ov = await http().get('/api/v1/relationships/overview').set(as(alice));
    expect(ov.body.relationships.find((r: any) => r.id === relId).flags.some((f: any) => f.code === 'LOOP_WAITING')).toBe(true);

    await http().post(`/api/v1/loops/${lid}/answer`).set(as(alice)).send({ kind: 'TEXT', text: 'Our call' }).expect(201);
    aliceState = (await http().get('/api/v1/loops').query({ conversationId: loop.body.conversationId }).set(as(alice))).body[0];
    expect(aliceState.revealed).toBe(true);
    expect(aliceState.answers.map((a: any) => a.text).sort()).toEqual(['Lunch with you', 'Our call']);
    expect(aliceState.completedTotal).toBe(1);
    await waitFor('bob', (f) => f.type === 'message.new' && (f.message as any)?.type === 'LOOP');

    const tl = await http().get(`/api/v1/relationships/${relId}/timeline`).set(as(alice));
    expect(tl.body.items.some((i: any) => i.kind === 'LOOP')).toBe(true);
  });

  it('erase & disconnect removes every shared conversation for both', async () => {
    if (skip()) return;
    await http().post(`/api/v1/messages/erase-with/${bob.userId}`).set(as(alice)).expect(201);
    await waitFor('bob', (f) => f.type === 'conversation.erased' && f.conversationId === cid);
    expect((await http().get('/api/v1/messages/conversations').set(as(bob))).body).toHaveLength(0);
    expect((await http().get('/api/v1/messages/conversations').set(as(alice))).body).toHaveLength(0);
  });
});
