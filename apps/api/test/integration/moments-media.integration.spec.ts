jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

/**
 * Moment media, stage C: watching and listening together.
 *
 * Two signed-in phones on the real socket. One brings a video from their own
 * phone; both see it, both follow the same clock through play, pause and seek;
 * a phone that comes back lands where the film is. What was shared is readable
 * only by people in the room, only while they are in it, and is gone when the
 * person who brought it leaves or the Moment ends.
 */
import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import * as WebSocket from 'ws';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { randomBytes } from 'crypto';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { MomentsService } from '../../src/moments/moments.service';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string };

const MEDIA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'moment-media-it-'));
/** A file that starts the way an MP4 does; the rest is noise, which is all a server should see. */
const film = Buffer.concat([Buffer.from('000000186674797069736f6d', 'hex'), randomBytes(50_000)]);

describe('Moment media: watching and listening together', () => {
  let app: INestApplication;
  let db: Client;
  let natasha: User; let mosty: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });

  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({ challengeId: otp.body.challengeId,
      code: '123456', devicePublicKey: `media-${n}`, platform: 'ANDROID', appVersion: 'media-test' }).expect(201)).body;
  }
  const start = async () => (await http().post('/api/v1/moments').set(auth(natasha))
    .send({ type: 'WATCHING', intent: 'WATCH', visibility: 'CONNECTIONS', durationMinutes: 60 }).expect(201)).body;
  const join = (u: User, id: string) => http().post(`/api/v1/moments/${id}/join`).set(auth(u)).expect(200);
  const share = (u: User, id: string, bytes = film, name = 'Our holiday.mp4', type = 'video/mp4') =>
    http().post(`/api/v1/moments/${id}/media`).set(auth(u))
      .attach('file', bytes, { filename: name, contentType: type }).field('durationMs', '60000');
  const play = (u: User, id: string, body: Record<string, unknown>, status = 200) =>
    http().post(`/api/v1/moments/${id}/playback`).set(auth(u)).send(body).expect(status);
  const streamUrl = async (u: User, id: string, mediaId: string) =>
    (await http().post(`/api/v1/moments/${id}/media/${mediaId}/stream`).set(auth(u)).expect(200)).body.url as string;
  const stored = () => fs.readdirSync(MEDIA_DIR).filter((f) => f.endsWith('.bin'));

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
    const playbackAt = (momentId: string, revision: number) =>
      waitFor((f) => f.type === 'moment.playback' && f.payload.momentId === momentId && f.payload.playback.revision === revision)
        .then((f) => f.payload);
    return { socket, frames, waitFor, playbackAt };
  }

  beforeAll(async () => {
    process.env.MOMENT_MEDIA_DIR = MEDIA_DIR;
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    natasha = await register('+260978750001'); mosty = await register('+260978750002'); stranger = await register('+260978750003');
    await db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`,
      [natasha.userId, mosty.userId]);
  }, 120000);
  afterAll(async () => {
    if (app) await app.close(); if (db) await db.end();
    fs.rmSync(MEDIA_DIR, { recursive: true, force: true });
  });
  beforeEach(async () => {
    await db.query('DELETE FROM moments');
    // Deleting rows directly skips the room closing, so the files are cleared here.
    for (const f of stored()) fs.rmSync(path.join(MEDIA_DIR, f), { force: true });
  });

  it('a video from her phone reaches both phones, encrypted on disk, readable in pieces', async () => {
    const m = await start();
    await join(mosty, m.id);
    const his = await phone(mosty);
    try {
      const shared = (await share(natasha, m.id).expect(201)).body;
      expect(shared).toMatchObject({ kind: 'VIDEO', title: 'Our holiday', durationMs: 60000, ownerUserId: natasha.userId });
      await his.waitFor((f) => f.type === 'moment.media' && f.payload.momentId === m.id);
      const listed = (await http().get(`/api/v1/moments/${m.id}/media`).set(auth(mosty)).expect(200)).body.media;
      expect(listed.map((x: any) => x.id)).toEqual([shared.id]);

      // On disk it is not the film.
      const [onDisk] = stored().map((f) => fs.readFileSync(path.join(MEDIA_DIR, f))).filter((b) => b.length === film.length);
      expect(onDisk.equals(film)).toBe(false);

      // Through the room it is, whole or in any piece.
      const url = await streamUrl(mosty, m.id, shared.id);
      const whole = await http().get(url).buffer(true).parse((res, cb) => {
        const parts: Buffer[] = []; res.on('data', (c: Buffer) => parts.push(c)); res.on('end', () => cb(null, Buffer.concat(parts)));
      }).expect(200);
      expect((whole.body as Buffer).equals(film)).toBe(true);
      const piece = await http().get(url).set('Range', 'bytes=1000-1999').buffer(true).parse((res, cb) => {
        const parts: Buffer[] = []; res.on('data', (c: Buffer) => parts.push(c)); res.on('end', () => cb(null, Buffer.concat(parts)));
      }).expect(206);
      expect(piece.headers['content-range']).toBe(`bytes 1000-1999/${film.length}`);
      expect((piece.body as Buffer).equals(film.subarray(1000, 2000))).toBe(true);
      await http().get(url).set('Range', `bytes=${film.length + 5}-`).expect(416);
    } finally { his.socket.close(); }
  });

  it('refuses what is not a video or song, even when it says it is one', async () => {
    const m = await start();
    const apk = Buffer.concat([Buffer.from('PK\x03\x04', 'latin1'), randomBytes(2000)]);
    await share(natasha, m.id, apk, 'film.mp4', 'video/mp4').expect(400);
    await share(natasha, m.id, apk, 'app.apk', 'application/vnd.android.package-archive').expect(400);
    expect(stored()).toHaveLength(0);
    expect(fs.readdirSync(path.join(MEDIA_DIR, 'incoming'))).toHaveLength(0);
  });

  it('only people in the room can share, list or play — and a borrowed or altered address gets nothing', async () => {
    const m = await start();
    await share(mosty, m.id).expect(403);
    await share(stranger, m.id).expect(404);
    const shared = (await share(natasha, m.id).expect(201)).body;
    await http().get(`/api/v1/moments/${m.id}/media`).set(auth(stranger)).expect(404);
    await http().post(`/api/v1/moments/${m.id}/media/${shared.id}/stream`).set(auth(mosty)).expect(403);

    const url = await streamUrl(natasha, m.id, shared.id);
    await http().get(url.replace(/s=[^&]+/, 's=AAAA')).expect(404);
    await http().get(url.replace(`u=${natasha.userId}`, `u=${mosty.userId}`)).expect(404);
    await http().get(url.replace(/e=\d+/, `e=${Date.now() + 999_999_999}`)).expect(404);
  });

  it('play, pause and seek move both phones together; a phone that comes back lands where the film is', async () => {
    const m = await start();
    await join(mosty, m.id);
    const hers = await phone(natasha); const his = await phone(mosty);
    try {
      const shared = (await share(natasha, m.id).expect(201)).body;
      await play(natasha, m.id, { op: 'LOAD', mediaId: shared.id });
      for (const p of [hers, his]) {
        expect((await p.playbackAt(m.id, 1)).playback).toMatchObject({ mediaId: shared.id, status: 'PAUSED', positionMs: 0, title: 'Our holiday' });
      }
      await play(natasha, m.id, { op: 'PLAY' });
      const playing = await his.playbackAt(m.id, 2);
      expect(playing.playback.status).toBe('PLAYING');
      expect(typeof playing.serverNow).toBe('number');

      // He pauses where he saw it; she stops on the same frame.
      await play(mosty, m.id, { op: 'PAUSE', positionMs: 12_345 });
      const hersPaused = (await hers.playbackAt(m.id, 3)).playback;
      const hisPaused = (await his.playbackAt(m.id, 3)).playback;
      expect(hersPaused).toEqual(hisPaused);
      expect(hersPaused).toMatchObject({ status: 'PAUSED', positionMs: 12_345, updatedBy: mosty.userId });

      await play(natasha, m.id, { op: 'SEEK', positionMs: 40_000 });
      expect((await his.playbackAt(m.id, 4)).playback.positionMs).toBe(40_000);
      await play(natasha, m.id, { op: 'PLAY' });
      await his.playbackAt(m.id, 5);

      // His phone drops out and comes back: the room tells it exactly where things are.
      his.socket.close();
      await new Promise((r) => setTimeout(r, 300));
      const room = (await http().get(`/api/v1/moments/${m.id}/room`).set(auth(mosty)).expect(200)).body;
      expect(room.playback).toMatchObject({ revision: 5, status: 'PLAYING', mediaId: shared.id });
      const now = room.serverNow as number;
      const expected = room.playback.positionMs + (now - room.playback.anchorAt);
      expect(expected).toBeGreaterThanOrEqual(40_000);
      expect(expected).toBeLessThan(45_000);
      expect(room.media.map((x: any) => x.id)).toEqual([shared.id]);
    } finally { hers.socket.close(); his.socket.close(); }
  });

  it('two people pressing at once both count, in order, and every phone ends the same', async () => {
    const m = await start();
    await join(mosty, m.id);
    const shared = (await share(natasha, m.id).expect(201)).body;
    await play(natasha, m.id, { op: 'LOAD', mediaId: shared.id });
    const [a, b] = await Promise.all([
      play(natasha, m.id, { op: 'PLAY' }),
      play(mosty, m.id, { op: 'SEEK', positionMs: 30_000 }),
    ]);
    expect([a.body.playback.revision, b.body.playback.revision].sort()).toEqual([2, 3]);
    const room = (await http().get(`/api/v1/moments/${m.id}/room`).set(auth(natasha)).expect(200)).body;
    expect(room.playback.revision).toBe(3);
  });

  it('refuses nonsense: play with nothing loaded, loading something from another room', async () => {
    const m = await start();
    await play(natasha, m.id, { op: 'PLAY' }, 400);
    await play(natasha, m.id, { op: 'LOAD', mediaId: '00000000-0000-4000-8000-000000000000' }, 400);
    await play(natasha, m.id, { op: 'EJECT' }, 400);
    await play(stranger, m.id, { op: 'STOP' }, 404);
  });

  it('what someone brought leaves with them, and stops if it was playing', async () => {
    const m = await start();
    await join(mosty, m.id);
    const hers = await phone(natasha);
    try {
      const his = (await share(mosty, m.id).expect(201)).body;
      await play(natasha, m.id, { op: 'LOAD', mediaId: his.id });
      await play(natasha, m.id, { op: 'PLAY' });
      const url = await streamUrl(natasha, m.id, his.id);
      expect(stored()).toHaveLength(1);

      await http().post(`/api/v1/moments/${m.id}/leave`).set(auth(mosty)).expect(200);
      const stopped = await hers.waitFor((f) => f.type === 'moment.playback' && f.payload.playback.status === 'IDLE');
      expect(stopped.payload.playback.mediaId).toBeNull();
      expect((await http().get(`/api/v1/moments/${m.id}/media`).set(auth(natasha)).expect(200)).body.media).toEqual([]);
      expect(stored()).toHaveLength(0);
      await http().get(url).expect(404);
    } finally { hers.socket.close(); }
  });

  it('only whoever shared something can take it back out', async () => {
    const m = await start();
    await join(mosty, m.id);
    const hers = (await share(natasha, m.id).expect(201)).body;
    await http().delete(`/api/v1/moments/${m.id}/media/${hers.id}`).set(auth(mosty)).expect(404);
    await http().delete(`/api/v1/moments/${m.id}/media/${hers.id}`).set(auth(natasha)).expect(200);
    expect(stored()).toHaveLength(0);
  });

  it('when the Moment ends, everything shared in it is deleted and no address works', async () => {
    const m = await start();
    await join(mosty, m.id);
    const shared = (await share(natasha, m.id).expect(201)).body;
    await play(natasha, m.id, { op: 'LOAD', mediaId: shared.id });
    const url = await streamUrl(mosty, m.id, shared.id);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(natasha)).expect(200);
    expect(stored()).toHaveLength(0);
    expect((await db.query('SELECT count(*)::int AS n FROM moment_media WHERE moment_id = $1', [m.id])).rows[0].n).toBe(0);
    await http().get(url).expect(404);
  });

  it('files nothing refers to are swept, but never one still in use or still arriving', async () => {
    const m = await start();
    const kept = (await share(natasha, m.id).expect(201)).body;
    const [keptFile] = stored();
    const old = new Date(Date.now() - 2 * 60 * 60 * 1000);
    fs.utimesSync(path.join(MEDIA_DIR, keptFile), old, old);
    const orphan = path.join(MEDIA_DIR, '11111111-1111-4111-8111-111111111111.bin');
    fs.writeFileSync(orphan, 'left behind'); fs.utimesSync(orphan, old, old);
    const arriving = path.join(MEDIA_DIR, '22222222-2222-4222-8222-222222222222.bin');
    fs.writeFileSync(arriving, 'just now');

    const { removed } = await app.get(MomentsService).sweepOrphanMedia();
    expect(removed).toBe(1);
    expect(fs.existsSync(orphan)).toBe(false);
    expect(fs.existsSync(arriving)).toBe(true);
    expect(stored()).toContain(keptFile);
    expect(kept.id).toBeTruthy();
    fs.rmSync(arriving, { force: true });
  });
});
