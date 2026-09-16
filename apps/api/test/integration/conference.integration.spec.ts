/**
 * Group (mesh) conference signaling end-to-end: create room, members join,
 * join fan-out, per-peer offer/answer/ICE relay, leave fan-out, and access control.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { readFileSync } from 'fs';
import { join } from 'path';
import { AddressInfo } from 'net';
import { WebSocket } from 'ws';
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

interface Sock {
  ws: WebSocket;
  messages: Record<string, unknown>[];
}

const sockets: WebSocket[] = [];

function open(port: number, token: string): Sock {
  const ws = new WebSocket(
    `ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${token}`,
  );
  sockets.push(ws);
  const messages: Record<string, unknown>[] = [];
  ws.on('message', (d) => {
    try {
      messages.push(JSON.parse(d.toString()));
    } catch {
      /* ignore */
    }
  });
  return { ws, messages };
}

function waitOpen(ws: WebSocket) {
  return new Promise<void>((res, rej) => {
    ws.once('open', () => res());
    ws.once('error', rej);
  });
}

async function delay(ms: number) {
  await new Promise((r) => setTimeout(r, ms));
}

function sendConf(
  ws: WebSocket,
  type: string,
  roomId: string,
  targetDeviceId?: string,
  payload?: Record<string, unknown>,
) {
  const data: Record<string, unknown> = { type, roomId };
  if (targetDeviceId) data.targetDeviceId = targetDeviceId;
  if (payload) data.payload = payload;
  ws.send(JSON.stringify({ event: 'conference', data }));
}

async function waitFor(
  sock: Sock,
  predicate: (m: Record<string, unknown>) => boolean,
  timeoutMs = 4000,
) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const found = sock.messages.find(predicate);
    if (found) return found;
    await delay(25);
  }
  throw new Error(`timeout; got ${JSON.stringify(sock.messages)}`);
}

describe('Conference (mesh) signaling end-to-end', () => {
  let app: INestApplication;
  let port = 0;
  let available = false;

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
    await app.listen(0);
    port = (app.getHttpServer().address() as AddressInfo).port;
  });

  afterAll(async () => {
    for (const ws of sockets) if (ws.readyState === WebSocket.OPEN) ws.close();
    await delay(300);
    if (app) await app.close();
  });

  const skip = () => !available;

  it('fans out joins and relays mesh offer/answer/ICE', async () => {
    if (skip()) return;
    const alice = await registerUser(app, '+260977000001', 'ga');
    const bob = await registerUser(app, '+260977000002', 'gb');
    const carol = await registerUser(app, '+260977000003', 'gc');

    const created = await request(app.getHttpServer())
      .post('/api/v1/conferences')
      .set('Authorization', `Bearer ${alice.accessToken}`)
      .send({ inviteeUserIds: [bob.userId, carol.userId] });
    expect([200, 201]).toContain(created.status);
    const roomId = created.body.roomId;
    expect(roomId).toBeTruthy();

    const a = open(port, alice.accessToken);
    const b = open(port, bob.accessToken);
    const c = open(port, carol.accessToken);
    await Promise.all([waitOpen(a.ws), waitOpen(b.ws), waitOpen(c.ws)]);
    await delay(200);

    sendConf(a.ws, 'conf.join', roomId);
    await delay(150);
    sendConf(b.ws, 'conf.join', roomId);
    // Alice is told Bob joined.
    const aSawBob = await waitFor(
      a,
      (m) => m.type === 'conf.peer-joined' && (m as any).deviceId === bob.deviceId,
    );
    expect(aSawBob).toBeTruthy();

    sendConf(c.ws, 'conf.join', roomId);
    // Both Alice and Bob learn Carol joined.
    await waitFor(a, (m) => m.type === 'conf.peer-joined' && (m as any).deviceId === carol.deviceId);
    await waitFor(b, (m) => m.type === 'conf.peer-joined' && (m as any).deviceId === carol.deviceId);

    // Mesh: Alice offers Bob, Bob answers, Carol gets ICE from Alice.
    sendConf(a.ws, 'conf.offer', roomId, bob.deviceId, { sdp: 'OFFER_AB' });
    const bobOffer = await waitFor(b, (m) => m.type === 'conf.offer');
    expect((bobOffer as any).fromDeviceId).toBe(alice.deviceId);
    expect(JSON.stringify((bobOffer as any).payload)).toContain('OFFER_AB');

    sendConf(b.ws, 'conf.answer', roomId, alice.deviceId, { sdp: 'ANSWER_BA' });
    const aliceAnswer = await waitFor(a, (m) => m.type === 'conf.answer');
    expect((aliceAnswer as any).fromDeviceId).toBe(bob.deviceId);

    sendConf(a.ws, 'conf.ice', roomId, carol.deviceId, { candidate: 'CAND_AC' });
    const carolIce = await waitFor(c, (m) => m.type === 'conf.ice');
    expect(JSON.stringify((carolIce as any).payload)).toContain('CAND_AC');

    // Carol leaves → Alice & Bob are notified.
    sendConf(c.ws, 'conf.leave', roomId);
    await waitFor(a, (m) => m.type === 'conf.peer-left' && (m as any).deviceId === carol.deviceId);
    await waitFor(b, (m) => m.type === 'conf.peer-left' && (m as any).deviceId === carol.deviceId);
  });

  it('denies joining a room you were not invited to', async () => {
    if (skip()) return;
    const owner = await registerUser(app, '+260977000011', 'go');
    const outsider = await registerUser(app, '+260977000012', 'gx');
    const created = await request(app.getHttpServer())
      .post('/api/v1/conferences')
      .set('Authorization', `Bearer ${owner.accessToken}`)
      .send({ inviteeUserIds: [] }); // only owner allowed
    const roomId = created.body.roomId;

    const x = open(port, outsider.accessToken);
    await waitOpen(x.ws);
    await delay(150);
    sendConf(x.ws, 'conf.join', roomId);
    const denied = await waitFor(x, (m) => JSON.stringify(m).includes('not_allowed'));
    expect(JSON.stringify(denied)).toContain('not_allowed');
  });
});
