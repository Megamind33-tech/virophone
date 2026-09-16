/**
 * End-to-end call signaling integration test.
 *
 * Drives two authenticated WebSocket clients (caller + callee) through the real
 * SignalingGateway and asserts that the full WebRTC handshake is relayed:
 *   invite → ringing → answer → ICE (both directions)
 * and that the call session reaches ACTIVE. Also verifies that signaling to a
 * peer whose socket is gone reports peer_unreachable instead of hanging.
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
import { CallSessionService } from '../../src/calls/call-session.service';

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
  const sql = readFileSync(
    join(__dirname, '../../src/database/migrations/001_initial_schema.sql'),
    'utf-8',
  );
  await client.query(sql);
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

async function allowCall(callerId: string, calleeId: string) {
  const client = new Client({ connectionString: DATABASE_URL });
  await client.connect();
  await client.query(
    `INSERT INTO contact_matches (user_id, matched_user_id, phone_hash, expires_at)
     VALUES ($1, $2, 'test-hash', NOW() + INTERVAL '1 day')
     ON CONFLICT (user_id, matched_user_id) DO NOTHING`,
    [callerId, calleeId],
  );
  await client.end();
}

interface Sock {
  ws: WebSocket;
  messages: Record<string, unknown>[];
}

const openSockets: WebSocket[] = [];

function openSocket(port: number, token: string): Sock {
  const ws = new WebSocket(
    `ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${token}`,
  );
  openSockets.push(ws);
  const messages: Record<string, unknown>[] = [];
  ws.on('message', (data) => {
    try {
      messages.push(JSON.parse(data.toString()));
    } catch {
      /* ignore non-JSON frames */
    }
  });
  return { ws, messages };
}

function waitOpen(ws: WebSocket): Promise<void> {
  return new Promise((resolve, reject) => {
    if (ws.readyState === WebSocket.OPEN) return resolve();
    ws.once('open', () => resolve());
    ws.once('error', reject);
  });
}

async function delay(ms: number) {
  await new Promise((r) => setTimeout(r, ms));
}

function deepIncludes(obj: unknown, needle: string): boolean {
  return JSON.stringify(obj ?? '').includes(needle);
}

async function waitFor(
  sock: Sock,
  predicate: (m: Record<string, unknown>) => boolean,
  timeoutMs = 5000,
): Promise<Record<string, unknown>> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const found = sock.messages.find(predicate);
    if (found) return found;
    await delay(25);
  }
  throw new Error(
    `Timed out waiting for message. Received: ${JSON.stringify(sock.messages)}`,
  );
}

function sendSig(
  ws: WebSocket,
  type: string,
  callId: string,
  targetDeviceId?: string,
  payload?: Record<string, unknown>,
) {
  const data: Record<string, unknown> = { type, callId };
  if (targetDeviceId) data.targetDeviceId = targetDeviceId;
  if (payload) data.payload = payload;
  ws.send(JSON.stringify({ event: 'signaling', data }));
}

describe('Call signaling end-to-end', () => {
  let app: INestApplication;
  let port = 0;
  let available = false;

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
    if (!available) return;

    await resetDatabase();
    app = await createTestApp();
    await app.listen(0);
    port = (app.getHttpServer().address() as AddressInfo).port;
  });

  afterAll(async () => {
    // Close client sockets and let the gateway process disconnects (which write
    // to Redis) BEFORE tearing down the app, otherwise Redis is quit mid-write.
    for (const ws of openSockets) {
      if (ws.readyState === WebSocket.OPEN) ws.close();
    }
    await delay(400);
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) {
      console.warn('Skipping: PostgreSQL or Redis unavailable');
      return true;
    }
    return false;
  };

  it('relays the full WebRTC handshake and marks the session ACTIVE', async () => {
    if (skip()) return;

    const caller = await registerUser(app, '+260979000001', 'caller-key');
    const callee = await registerUser(app, '+260979000002', 'callee-key');
    await allowCall(caller.userId, callee.userId);

    const callerSock = openSocket(port, caller.accessToken);
    const calleeSock = openSocket(port, callee.accessToken);
    await Promise.all([waitOpen(callerSock.ws), waitOpen(calleeSock.ws)]);
    // Let the gateway persist presence (ws:user:*) before authorizing.
    await delay(300);

    const authRes = await request(app.getHttpServer())
      .post('/api/v1/calls/authorize')
      .set('Authorization', `Bearer ${caller.accessToken}`)
      .send({ targetUserId: callee.userId });
    expect([200, 201]).toContain(authRes.status);
    expect(authRes.body.authorized).toBe(true);
    const callId: string = authRes.body.callId;
    expect(authRes.body.sessionMaterial.calleeDeviceId).toBe(callee.deviceId);

    // Caller → invite (with SDP offer)
    sendSig(callerSock.ws, 'call.invite', callId, callee.deviceId, {
      sdp: 'v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n',
      type: 'offer',
    });
    const invite = await waitFor(calleeSock, (m) => m.type === 'call.invite');
    expect(invite.callId).toBe(callId);
    expect(invite.fromDeviceId).toBe(caller.deviceId);
    expect(deepIncludes(invite.payload, 'm=audio')).toBe(true);

    // Callee → ringing
    sendSig(calleeSock.ws, 'call.ringing', callId, caller.deviceId);
    const ringing = await waitFor(callerSock, (m) => m.type === 'call.ringing');
    expect(ringing.fromDeviceId).toBe(callee.deviceId);

    // Callee → answer (with SDP answer). This should move the session to ACTIVE.
    sendSig(calleeSock.ws, 'call.answer', callId, caller.deviceId, {
      sdp: 'v=0\r\no=- 2 2 IN IP4 0.0.0.0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n',
      type: 'answer',
    });
    const answer = await waitFor(callerSock, (m) => m.type === 'call.answer');
    expect(deepIncludes(answer.payload, 'm=audio')).toBe(true);

    // ICE candidates both directions
    sendSig(callerSock.ws, 'call.ice', callId, callee.deviceId, {
      candidate: 'candidate:1 1 udp 2122260223 10.0.0.1 54321 typ host',
      sdpMid: '0',
      sdpMLineIndex: 0,
    });
    sendSig(calleeSock.ws, 'call.ice', callId, caller.deviceId, {
      candidate: 'candidate:2 1 udp 2122260223 10.0.0.2 54322 typ host',
      sdpMid: '0',
      sdpMLineIndex: 0,
    });
    const calleeIce = await waitFor(calleeSock, (m) => m.type === 'call.ice');
    const callerIce = await waitFor(callerSock, (m) => m.type === 'call.ice');
    expect(deepIncludes(calleeIce.payload, '10.0.0.1')).toBe(true);
    expect(deepIncludes(callerIce.payload, '10.0.0.2')).toBe(true);

    // Session state should now be ACTIVE (set on call.answer).
    const session = await app.get(CallSessionService).getSession(callId);
    expect(session?.state).toBe('ACTIVE');

    callerSock.ws.close();
    calleeSock.ws.close();
  });

  it('reports peer_unreachable when the callee socket is gone', async () => {
    if (skip()) return;

    const caller = await registerUser(app, '+260979000011', 'c2-key');
    const callee = await registerUser(app, '+260979000012', 'd2-key');
    await allowCall(caller.userId, callee.userId);

    const callerSock = openSocket(port, caller.accessToken);
    const calleeSock = openSocket(port, callee.accessToken);
    await Promise.all([waitOpen(callerSock.ws), waitOpen(calleeSock.ws)]);
    await delay(300);

    const authRes = await request(app.getHttpServer())
      .post('/api/v1/calls/authorize')
      .set('Authorization', `Bearer ${caller.accessToken}`)
      .send({ targetUserId: callee.userId });
    const callId: string = authRes.body.callId;

    // Callee drops off the network.
    calleeSock.ws.close();
    await delay(300);

    callerSock.messages.length = 0;
    sendSig(callerSock.ws, 'call.ice', callId, callee.deviceId, {
      candidate: 'candidate:1 1 udp 1 10.0.0.9 5 typ host',
      sdpMid: '0',
      sdpMLineIndex: 0,
    });

    const ack = await waitFor(callerSock, (m) =>
      deepIncludes(m, 'peer_unreachable'),
    );
    expect(deepIncludes(ack, 'peer_unreachable')).toBe(true);

    callerSock.ws.close();
  });
});
