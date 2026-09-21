jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

/**
 * Moment presence, stage B: faces and voices in a Moment, for the people in
 * it and no one else.
 *
 * Video is allowed in Moments only, so the token here is the one place a
 * camera is granted. Holding it must not be enough to stay: leaving, a block
 * or the Moment ending takes a person out of the media room as well.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import * as WebSocket from 'ws';
import { TokenVerifier } from 'livekit-server-sdk';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { LiveKitService } from '../../src/livekit/livekit.service';
import { MomentsService } from '../../src/moments/moments.service';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string };

describe('Moment presence: live faces and voices for the people in the room', () => {
  let app: INestApplication;
  let db: Client;
  let livekit: LiveKitService;
  let natasha: User; let mosty: User; let chipo: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });
  const verifier = () => new TokenVerifier(process.env.LIVEKIT_API_KEY!, process.env.LIVEKIT_API_SECRET!);

  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({ challengeId: otp.body.challengeId,
      code: '123456', devicePublicKey: `presence-${n}`, platform: 'ANDROID', appVersion: 'presence-test' }).expect(201)).body;
  }
  const connect = (a: User, b: User) => db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status)
    VALUES ($1,$2,'ACCEPTED')`, [a.userId, b.userId]);
  const start = async (u = natasha, intent = 'COOK') =>
    (await http().post('/api/v1/moments').set(auth(u))
      .send({ type: 'FREE', intent, visibility: 'CONNECTIONS', durationMinutes: 60 }).expect(201)).body;
  const join = (u: User, id: string, status = 200) => http().post(`/api/v1/moments/${id}/join`).set(auth(u)).expect(status);
  const presence = (u: User, id: string) => http().post(`/api/v1/moments/${id}/presence`).set(auth(u));
  const inRoom = async (id: string) => (await db.query(`SELECT user_id FROM moment_participants WHERE moment_id = $1`, [id]))
    .rows.map((r) => r.user_id).sort();

  async function phone(u: User) {
    const port = app.getHttpServer().address().port;
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${u.accessToken}`);
    const frames: any[] = [];
    socket.on('message', (raw) => frames.push(JSON.parse(raw.toString())));
    await new Promise<void>((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
    await new Promise((r) => setTimeout(r, 100));
    const waitFor = async (match: (f: any) => boolean) => {
      for (let i = 0; i < 200; i++) {
        const found = frames.find(match);
        if (found) return found;
        await new Promise((r) => setTimeout(r, 20));
      }
      throw new Error('Frame never arrived');
    };
    return { socket, frames, waitFor };
  }

  beforeAll(async () => {
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    // Several modules each provide their own LiveKitService; this is the Moments one.
    livekit = (app.get(MomentsService) as any).livekit as LiveKitService;
    natasha = await register('+260978740001'); mosty = await register('+260978740002');
    chipo = await register('+260978740003'); stranger = await register('+260978740004');
    await connect(natasha, mosty); await connect(natasha, chipo);
  }, 120000);
  afterAll(async () => { if (app) await app.close(); if (db) await db.end(); });
  beforeEach(async () => {
    await db.query('DELETE FROM moments'); await db.query('DELETE FROM blocks');
    jest.restoreAllMocks();
  });

  it('admits both people to the same media room, with a camera and microphone and nothing else', async () => {
    const m = await start();
    await join(mosty, m.id);
    const host = await presence(natasha, m.id).expect(200);
    const guest = await presence(mosty, m.id).expect(200);
    expect(host.body.roomName).toBe(`viro-moment-${m.id}`);
    expect(guest.body.roomName).toBe(host.body.roomName);
    expect(host.body.url).toBe(process.env.LIVEKIT_URL);

    const claims = await verifier().verify(guest.body.token);
    expect(claims.sub).toBe(mosty.userId);
    expect(claims.video?.room).toBe(`viro-moment-${m.id}`);
    expect(claims.video?.roomJoin).toBe(true);
    expect(claims.video?.canSubscribe).toBe(true);
    expect([...(claims.video?.canPublishSources ?? [])].sort()).toEqual(['camera', 'microphone']);
    // No screen sharing, and no side channel around Viro's own socket.
    expect(claims.video?.canPublishData).toBe(false);
    // The name other phones see is a name, never an identifier.
    expect(claims.name).toBeTruthy();
    expect(claims.name).not.toBe(mosty.userId);
    expect(JSON.stringify(guest.body)).not.toContain(process.env.LIVEKIT_API_SECRET);
  });

  it('keeps calls voice-only: the camera belongs to Moments', async () => {
    const creds = await livekit.generateTokenForRoom('viro-call-x', natasha.userId);
    const claims = await verifier().verify(creds.token);
    expect(claims.video?.canPublishSources).toEqual(['microphone']);
  });

  it('refuses someone who can see the Moment but has not come in, and a stranger learns nothing', async () => {
    const m = await start();
    await presence(mosty, m.id).expect(403);
    await presence(stranger, m.id).expect(404);
  });

  it('says plainly when live video is not available, instead of failing silently', async () => {
    const m = await start();
    jest.spyOn(livekit, 'isConfigured').mockReturnValue(false);
    const res = await presence(natasha, m.id).expect(503);
    expect(res.body.message ?? res.body.error?.message ?? JSON.stringify(res.body)).toContain("Live video isn't available");
  });

  it('takes someone who leaves out of the media room, and ending closes it', async () => {
    const removed = jest.spyOn(livekit, 'removeFromRoom').mockResolvedValue();
    const closed = jest.spyOn(livekit, 'closeRoom').mockResolvedValue();
    const m = await start();
    await join(mosty, m.id);
    await http().post(`/api/v1/moments/${m.id}/leave`).set(auth(mosty)).expect(200);
    expect(removed).toHaveBeenCalledWith(`viro-moment-${m.id}`, mosty.userId);
    await presence(mosty, m.id).expect(403);

    await http().delete(`/api/v1/moments/${m.id}`).set(auth(natasha)).expect(200);
    expect(closed).toHaveBeenCalledWith(`viro-moment-${m.id}`);
    await presence(natasha, m.id).expect(404);
  });

  it('a block during a Moment separates them at once: out of the room, out of the media, and cannot come back', async () => {
    const removed = jest.spyOn(livekit, 'removeFromRoom').mockResolvedValue();
    const m = await start();
    await join(mosty, m.id);
    const hisPhone = await phone(mosty);
    try {
      await http().post('/api/v1/blocks').set(auth(natasha)).send({ blockedUserId: mosty.userId }).expect(201);
      expect(await inRoom(m.id)).toEqual([natasha.userId]);
      expect(removed).toHaveBeenCalledWith(`viro-moment-${m.id}`, mosty.userId);
      // His phone is told it is out, so it closes the room and its camera.
      await hisPhone.waitFor((f) => f.type === 'moment.left' && f.payload.momentId === m.id && f.payload.userId === mosty.userId);
      await presence(mosty, m.id).expect(404);
      await join(mosty, m.id, 404);
    } finally { hisPhone.socket.close(); }
  });

  it("in someone else's Moment, the person blocked leaves and the host's room goes on", async () => {
    jest.spyOn(livekit, 'removeFromRoom').mockResolvedValue();
    const m = await start();
    await join(mosty, m.id);
    await join(chipo, m.id);
    await http().post('/api/v1/blocks').set(auth(mosty)).send({ blockedUserId: chipo.userId }).expect(201);
    expect(await inRoom(m.id)).toEqual([natasha.userId, mosty.userId].sort());
    // While the person who blocked them is there, they cannot share this room —
    // and the refusal is the same one any unavailable Moment gives.
    const refused = await join(chipo, m.id, 404);
    expect(JSON.stringify(refused.body)).not.toMatch(/block/i);
    await presence(natasha, m.id).expect(200);
  });

  it('a block made before joining keeps them apart from the start', async () => {
    const m = await start();
    await join(mosty, m.id);
    await db.query(`INSERT INTO blocks(blocker_user_id, blocked_user_id) VALUES ($1,$2)`, [chipo.userId, mosty.userId]);
    await join(chipo, m.id, 404);
    expect(await inRoom(m.id)).toEqual([natasha.userId, mosty.userId].sort());
  });
});
