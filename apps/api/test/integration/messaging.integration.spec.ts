/**
 * Messaging subsystem end-to-end test: send → persist → realtime deliver →
 * history → unread/read receipts → idempotent resend.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { AddressInfo } from 'net';
import { WebSocket } from 'ws';
import { createTestApp } from './test-app';
import { RedisService } from '../../src/redis/redis.service';

const DATABASE_URL =
  process.env.DATABASE_URL ||
  'postgresql://viro:viro_dev_password@localhost:5432/viro_reach';

// Use the complete current schema, including Moments and encryption.
import { resetDatabase } from './reset-db';

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

async function delay(ms: number) {
  await new Promise((r) => setTimeout(r, ms));
}

describe('Messaging end-to-end', () => {
  let app: INestApplication;
  let port = 0;
  let available = false;
  const sockets: WebSocket[] = [];

  beforeAll(async () => {
    try {
      const client = new Client({ connectionString: DATABASE_URL });
      await client.connect();
      await client.query('SELECT 1');
      await client.end();
      const redis = new RedisService();
      available = await redis.ping();
      await redis.onModuleDestroy();
    } catch {
      available = false;
    }
    if (!available) throw new Error('PostgreSQL and Redis are required for this integration suite.');
    await resetDatabase();
    app = await createTestApp();
    await app.listen(0);
    port = (app.getHttpServer().address() as AddressInfo).port;
  });

  afterAll(async () => {
    for (const ws of sockets) if (ws.readyState === WebSocket.OPEN) ws.close();
    await delay(300);
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) {
      console.warn('Skipping: PostgreSQL or Redis unavailable');
      return true;
    }
    return false;
  };

  it('delivers a DM in realtime, persists it, and tracks unread/read', async () => {
    if (skip()) return;
    const alice = await registerUser(app, '+260978000001', 'a-key');
    const bob = await registerUser(app, '+260978000002', 'b-key');

    // Bob connects his signaling socket so realtime delivery can reach him.
    const bobWs = new WebSocket(
      `ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${bob.accessToken}`,
    );
    sockets.push(bobWs);
    const bobMessages: Record<string, unknown>[] = [];
    bobWs.on('message', (d) => {
      try {
        bobMessages.push(JSON.parse(d.toString()));
      } catch {
        /* ignore */
      }
    });
    await new Promise<void>((res, rej) => {
      bobWs.once('open', () => res());
      bobWs.once('error', rej);
    });
    await delay(300);

    // Alice sends Bob a message.
    const send = await request(app.getHttpServer())
      .post('/api/v1/messages')
      .set('Authorization', `Bearer ${alice.accessToken}`)
      .send({ toUserId: bob.userId, body: 'Hello Bob', clientMsgId: 'c-1' });
    expect([200, 201]).toContain(send.status);
    const conversationId = send.body.conversationId;
    expect(conversationId).toBeTruthy();
    expect(send.body.message.body).toBe('Hello Bob');

    // Bob receives it over the socket in realtime.
    const start = Date.now();
    let delivered: Record<string, unknown> | undefined;
    while (Date.now() - start < 4000 && !delivered) {
      delivered = bobMessages.find((m) => m.type === 'message.new');
      if (!delivered) await delay(25);
    }
    expect(delivered).toBeTruthy();
    expect((delivered as any).conversationId).toBe(conversationId);
    expect((delivered as any).message.body).toBe('Hello Bob');

    // Conversation appears for Bob with unread=1.
    const convos = await request(app.getHttpServer())
      .get('/api/v1/messages/conversations')
      .set('Authorization', `Bearer ${bob.accessToken}`);
    expect(convos.status).toBe(200);
    const convo = convos.body.find((c: any) => c.id === conversationId);
    expect(convo).toBeTruthy();
    expect(convo.unread).toBe(1);
    expect(convo.lastMessage.body).toBe('Hello Bob');

    // History returns the message.
    const history = await request(app.getHttpServer())
      .get(`/api/v1/messages/conversations/${conversationId}`)
      .set('Authorization', `Bearer ${bob.accessToken}`);
    expect(history.status).toBe(200);
    expect(history.body.length).toBe(1);
    expect(history.body[0].body).toBe('Hello Bob');

    // Idempotent resend with same clientMsgId does not duplicate.
    const resend = await request(app.getHttpServer())
      .post('/api/v1/messages')
      .set('Authorization', `Bearer ${alice.accessToken}`)
      .send({ toUserId: bob.userId, body: 'Hello Bob', clientMsgId: 'c-1' });
    expect(resend.body.message.id).toBe(send.body.message.id);
    const history2 = await request(app.getHttpServer())
      .get(`/api/v1/messages/conversations/${conversationId}`)
      .set('Authorization', `Bearer ${bob.accessToken}`);
    expect(history2.body.length).toBe(1);

    // Bob marks read → unread resets to 0.
    await request(app.getHttpServer())
      .post(`/api/v1/messages/conversations/${conversationId}/read`)
      .set('Authorization', `Bearer ${bob.accessToken}`)
      .expect((r) => expect([200, 201]).toContain(r.status));
    const convos2 = await request(app.getHttpServer())
      .get('/api/v1/messages/conversations')
      .set('Authorization', `Bearer ${bob.accessToken}`);
    expect(convos2.body.find((c: any) => c.id === conversationId).unread).toBe(0);
  });

  it('rejects messaging a blocked user', async () => {
    if (skip()) return;
    const alice = await registerUser(app, '+260978000011', 'a2-key');
    const bob = await registerUser(app, '+260978000012', 'b2-key');

    await request(app.getHttpServer())
      .post('/api/v1/blocks')
      .set('Authorization', `Bearer ${bob.accessToken}`)
      .send({ blockedUserId: alice.userId })
      .expect((r) => expect([200, 201]).toContain(r.status));

    const send = await request(app.getHttpServer())
      .post('/api/v1/messages')
      .set('Authorization', `Bearer ${alice.accessToken}`)
      .send({ toUserId: bob.userId, body: 'hi' });
    expect(send.status).toBe(403);
  });

  it('accepts a send whose sealed copies exceed the 100 KB express default', async () => {
    if (skip()) return;
    const alice = await registerUser(app, '+260978000021', 'a3-key');
    // The send DTO allows 512 envelopes — one sealed copy per recipient
    // device — and fresh-session ciphertexts make such a body far larger than
    // express's 100 KB JSON default, which once refused every real encrypted
    // send as "request entity too large" (shown as a generic unexpected
    // error). The device ids are not real, so the outcome is a 4xx from
    // validation; it must never be 413 from the body parser.
    const envelopes = Array.from({ length: 512 }, (_, i) => ({
      deviceId: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
      ciphertext: 'A'.repeat(2048),
    }));
    const res = await request(app.getHttpServer())
      .post('/api/v1/messages')
      .set('Authorization', `Bearer ${alice.accessToken}`)
      .send({ toUserId: alice.userId, body: 'big', clientMsgId: 'c-big', envelopes });
    expect(res.status).not.toBe(413);
  });
});
