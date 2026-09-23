jest.mock('ioredis', () => {
  if (process.env.REDIS_MOCK === '1') {
    const Mock = require('ioredis-mock');
    return { __esModule: true, default: Mock, Redis: Mock };
  }
  return jest.requireActual('ioredis');
});

import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import * as WebSocket from 'ws';
import { createTestApp } from './test-app';
import { resetDatabase, DATABASE_URL } from './reset-db';
import { MomentsClock } from '../../src/moments/moments.module';
import { MomentsService } from '../../src/moments/moments.service';
import { RealtimeRegistry } from '../../src/realtime/realtime.registry';
import { SignalingGateway } from '../../src/signaling/signaling.gateway';

jest.setTimeout(60000);
type User = { userId: string; accessToken: string };
describe('Viro Now Phase 2: rooms, chat, reactions, knocks and invitations', () => {
  let app: INestApplication;
  let db: Client;
  let a: User; let b: User; let stranger: User;
  const http = () => request(app.getHttpServer());
  const auth = (u: User) => ({ Authorization: `Bearer ${u.accessToken}` });
  const create = async (u = a, overrides = {}) =>
    (await http().post('/api/v1/moments').set(auth(u)).send({ type: 'WATCHING', visibility: 'CONNECTIONS', durationMinutes: 15, ...overrides }).expect(201)).body;
  const join = async (u: User, id: string) =>
    (await http().post(`/api/v1/moments/${id}/join`).set(auth(u)).expect(200)).body;
  async function register(n: string): Promise<User> {
    const otp = await http().post('/api/v1/auth/otp/request').send({ phoneE164: n }).expect(201);
    return (await http().post('/api/v1/auth/otp/verify').send({ challengeId: otp.body.challengeId,
      code: '123456', devicePublicKey: `rooms-${n}`, platform: 'ANDROID', appVersion: 'rooms-test' }).expect(201)).body;
  }
  const roomOf = async (u: User, id: string, status = 200) =>
    (await http().get(`/api/v1/moments/${id}/room`).set(auth(u)).expect(status)).body;

  beforeAll(async () => {
    await resetDatabase();
    db = new Client({ connectionString: DATABASE_URL }); await db.connect();
    app = await createTestApp({ manyOtpFixtures: true }); await app.listen(0, '127.0.0.1');
    a = await register('+260978720001'); b = await register('+260978720002'); stranger = await register('+260978720003');
    await db.query(`INSERT INTO viro_connections(requester_user_id,recipient_user_id,status) VALUES ($1,$2,'ACCEPTED')`, [a.userId, b.userId]);
  }, 120000);
  afterAll(async () => { if (app) await app.close(); if (db) await db.end(); });
  beforeEach(async () => {
    await db.query('DELETE FROM moments'); await db.query('DELETE FROM blocks'); await db.query('DELETE FROM contact_matches');
    await db.query(`UPDATE viro_connections SET status = 'ACCEPTED'`);
  });

  it('requires authentication on every room route', async () => {
    const m = await create();
    for (const [method, path] of [
      ['post', `/api/v1/moments/${m.id}/join`], ['post', `/api/v1/moments/${m.id}/leave`],
      ['get', `/api/v1/moments/${m.id}/room`], ['post', `/api/v1/moments/${m.id}/messages`],
      ['post', `/api/v1/moments/${m.id}/knock`], ['get', `/api/v1/moments/${m.id}/knocks`],
      ['post', `/api/v1/moments/${m.id}/invites`], ['get', '/api/v1/moments/invitations'],
    ] as const) {
      await (http() as any)[method](path).expect(401);
    }
  });

  it('runs the two-account acceptance loop: join, chat, react, leave — realtime, no restart', async () => {
    const m = await create();
    // The host is a participant from creation; the room starts at one.
    const hostRoom = await roomOf(a, m.id);
    expect(hostRoom.participants.map((p: any) => p.userId)).toEqual([a.userId]);
    expect(hostRoom.participants[0].isHost).toBe(true);
    expect(m.participantCount).toBe(1);

    const guestRoom = await join(b, m.id);
    expect(guestRoom.participants.map((p: any) => p.userId).sort()).toEqual([a.userId, b.userId].sort());
    // Both sides agree on two participants (§33) without any restart.
    expect((await roomOf(a, m.id)).participants).toHaveLength(2);

    const sent = (await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(b)).send({ body: "I'm in" }).expect(201)).body;
    const withMessage = await roomOf(a, m.id);
    expect(withMessage.messages).toHaveLength(1);
    expect(withMessage.messages[0]).toMatchObject({ body: "I'm in", senderUserId: b.userId });

    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(a))
      .send({ emoji: '🔥' }).expect(200);
    expect((await roomOf(b, m.id)).messages[0].reactions).toEqual([{ emoji: '🔥', userIds: [a.userId] }]);

    await http().post(`/api/v1/moments/${m.id}/leave`).set(auth(b)).expect(200);
    const afterLeave = await roomOf(a, m.id);
    expect(afterLeave.participants.map((p: any) => p.userId)).toEqual([a.userId]);
    // The conversation never became a permanent chat (§35).
    expect((await db.query(`SELECT count(*)::int AS n FROM conversations`)).rows[0].n).toBe(0);
    expect((await db.query(`SELECT count(*)::int AS n FROM messages`)).rows[0].n).toBe(0);
  });

  it('makes join idempotent so reconnects cannot duplicate participants or announcements', async () => {
    const m = await create();
    const spy = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    await join(b, m.id); await join(b, m.id); await join(b, m.id);
    expect((await roomOf(a, m.id)).participants).toHaveLength(2);
    // One announcement, fanned out once to each current participant: the host
    // hears "B joined" exactly once no matter how many times B re-joined.
    const joined = spy.mock.calls.filter(c => c[1]?.type === 'moment.joined');
    expect(joined.filter(c => c[0] === a.userId)).toHaveLength(1);
    expect(joined.map(c => (c[1].payload as { userId?: string }).userId).every(uid => uid === b.userId)).toBe(true);
    spy.mockRestore();
  });

  it('keeps strangers and blocked users out of the room by direct API', async () => {
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(stranger)).expect(404);
    await http().get(`/api/v1/moments/${m.id}/room`).set(auth(stranger)).expect(404);
    // A block in either direction removes an outsider's room access (§23).
    await http().post('/api/v1/blocks').set(auth(a)).send({ blockedUserId: b.userId }).expect(201);
    await http().get(`/api/v1/moments/${m.id}/room`).set(auth(b)).expect(404);
    // Leaving still works: removing your own membership discloses nothing.
    await http().post(`/api/v1/moments/${m.id}/leave`).set(auth(b)).expect(200);
  });

  it('restricts chat and reactions to current participants of a live Moment', async () => {
    const m = await create();
    const sent = (await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a)).send({ body: 'hosting' }).expect(201)).body;
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(b)).send({ body: 'not in yet' }).expect(403);
    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(b)).send({ emoji: '👍' }).expect(403);
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(b)).send({ emoji: '🎉' }).expect(400);
    // Re-reacting switches the emoji rather than stacking a second one.
    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(b)).send({ emoji: '❤️' }).expect(200);
    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(b)).send({ emoji: '❤️' }).expect(200);
    let room = await roomOf(b, m.id);
    expect(room.messages[0].reactions).toEqual([{ emoji: '❤️', userIds: [b.userId] }]);
    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(b)).send({ emoji: null }).expect(200);
    room = await roomOf(b, m.id);
    expect(room.messages[0].reactions).toEqual([]);
    // Reactions on a message from another room are not addressable.
    const other = await create(b);
    const otherMsg = (await http().post(`/api/v1/moments/${other.id}/messages`).set(auth(b)).send({ body: 'mine' }).expect(201)).body;
    await http().post(`/api/v1/moments/${m.id}/messages/${otherMsg.id}/react`).set(auth(a)).send({ emoji: '👍' }).expect(404);
  });

  it('knocks on Free Moments only, notifies only the host, and records the response', async () => {
    const watching = await create();
    await http().post(`/api/v1/moments/${watching.id}/knock`).set(auth(b)).expect(400); // not FREE
    // One active Moment per host (Phase 1): close the first before the next.
    await http().delete(`/api/v1/moments/${watching.id}`).set(auth(a)).expect(200);
    const m = await create(a, { type: 'FREE' });
    await http().post(`/api/v1/moments/${m.id}/knock`).set(auth(a)).expect(400); // the host themself
    await http().post(`/api/v1/moments/${m.id}/knock`).set(auth(stranger)).expect(404); // invisible audience
    const spy = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    await http().post(`/api/v1/moments/${m.id}/knock`).set(auth(b)).expect(200);
    const knockFrame = spy.mock.calls.find(c => c[1]?.type === 'moment.knock');
    expect(knockFrame?.[0]).toBe(a.userId);
    expect(knockFrame?.[1].payload).toMatchObject({ momentId: m.id, knockerUserId: b.userId });
    expect(spy.mock.calls.filter(c => c[1]?.type === 'moment.knock').map(c => c[0])).toEqual([a.userId]);
    expect((await http().get(`/api/v1/moments/${m.id}/knocks`).set(auth(a)).expect(200)).body.knocks)
      .toEqual([expect.objectContaining({ knockerUserId: b.userId })]);
    // Only the host may list or answer knocks.
    await http().get(`/api/v1/moments/${m.id}/knocks`).set(auth(b)).expect(404);
    await http().post(`/api/v1/moments/${m.id}/knocks/${b.userId}/respond`).set(auth(b)).send({ accept: true }).expect(404);
    await http().post(`/api/v1/moments/${m.id}/knocks/${b.userId}/respond`).set(auth(a)).send({ accept: true }).expect(200);
    expect((await http().get(`/api/v1/moments/${m.id}/knocks`).set(auth(a)).expect(200)).body.knocks).toEqual([]);
    // Answering twice is not an error state the host can act on again.
    await http().post(`/api/v1/moments/${m.id}/knocks/${b.userId}/respond`).set(auth(a)).send({ accept: false }).expect(404);
    spy.mockRestore();
  });

  it('knock falls back to push when the host has no live socket', async () => {
    const m = await create(a, { type: 'FREE' });
    const offline = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser').mockResolvedValue(false);
    const { PushService } = await import('../../src/push/push.service');
    const pushSpy = jest.spyOn(app.get(PushService), 'sendToUser').mockResolvedValue();
    await http().post(`/api/v1/moments/${m.id}/knock`).set(auth(b)).expect(200);
    expect(pushSpy).toHaveBeenCalledTimes(1);
    expect(pushSpy.mock.calls[0][0]).toBe(a.userId);
    expect(pushSpy.mock.calls[0][1]).toMatchObject({ data: { type: 'moment-knock', momentId: m.id } });
    pushSpy.mockRestore(); offline.mockRestore();
  });

  it('invites accepted connections only, and the invitee can decline or join from the invitation', async () => {
    const m = await create(a, { mood: 'SAD', intent: 'TALK', invitationText: 'Can we talk?' });
    await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(b)).send({ userId: a.userId }).expect(403); // not host
    await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(a)).send({ userId: stranger.userId }).expect(400); // not connected
    await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(a)).send({ userId: a.userId }).expect(400); // self
    const spy = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(a)).send({ userId: b.userId }).expect(200);
    expect(spy.mock.calls.find(c => c[1]?.type === 'moment.invited')?.[0]).toBe(b.userId);
    expect(spy.mock.calls.find(c => c[1]?.type === 'moment.invited')?.[1].payload).toMatchObject({
      momentId: m.id, mood: 'SAD', told: expect.stringContaining('is a bit low and would like to talk'),
    });
    spy.mockRestore();
    let invitations = (await http().get('/api/v1/moments/invitations').set(auth(b)).expect(200)).body.invitations;
    expect(invitations).toHaveLength(1);
    expect(invitations[0].moment.id).toBe(m.id);
    expect(invitations[0].moment).toMatchObject({ mood: 'SAD', intent: 'TALK', invitationText: 'Can we talk?' });
    expect((await http().get('/api/v1/moments/invitations').set(auth(stranger)).expect(200)).body.invitations).toEqual([]);
    // A blocked invitee stops seeing the invitation (§23), without acting on it.
    await http().post('/api/v1/blocks').set(auth(a)).send({ blockedUserId: b.userId }).expect(201);
    expect((await http().get('/api/v1/moments/invitations').set(auth(b)).expect(200)).body.invitations).toEqual([]);
    await db.query('DELETE FROM blocks');
    invitations = (await http().get('/api/v1/moments/invitations').set(auth(b)).expect(200)).body.invitations;
    expect(invitations).toHaveLength(1);
    await http().delete(`/api/v1/moments/invitations/${invitations[0].invitationId}`).set(auth(b)).expect(200);
    expect((await http().get('/api/v1/moments/invitations').set(auth(b)).expect(200)).body.invitations).toEqual([]);
    // Declining someone else's invitation is not possible.
    await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(a)).send({ userId: b.userId }).expect(200);
    const again = (await http().get('/api/v1/moments/invitations').set(auth(b)).expect(200)).body.invitations;
    await http().delete(`/api/v1/moments/invitations/${again[0].invitationId}`).set(auth(a)).expect(404);
  });

  it('does not report a sent invitation when its recipient is outside the audience', async () => {
    const m = await create(a, { visibility: 'CONTACTS', mood: 'SAD' });
    const spy = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    try {
      await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(a)).send({ userId: b.userId }).expect(404);
      expect(spy.mock.calls.filter(c => c[1]?.type === 'moment.invited')).toHaveLength(0);
      expect((await db.query('SELECT * FROM moment_invitations WHERE moment_id=$1', [m.id])).rows).toHaveLength(0);
    } finally { spy.mockRestore(); }
  });

  it('closes the room when the Moment ends: no joins, messages, reactions or participants remain', async () => {
    const m = await create(a, { type: 'FREE' });
    await join(b, m.id);
    const sent = (await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(b)).send({ body: 'before close' }).expect(201)).body;
    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(a)).send({ emoji: '👏' }).expect(200);
    await http().post(`/api/v1/moments/${m.id}/knock`).set(auth(b)).expect(400); // already a participant

    // The host cannot quietly abandon their own room by leaving (§18/§19).
    await http().post(`/api/v1/moments/${m.id}/leave`).set(auth(a)).expect(400);
    await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);

    await http().post(`/api/v1/moments/${m.id}/join`).set(auth(b)).expect(404);
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(b)).send({ body: 'after close' }).expect(404);
    await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(b)).send({ emoji: '👍' }).expect(404);
    await http().post(`/api/v1/moments/${m.id}/knock`).set(auth(b)).expect(404);
    await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(a)).send({ userId: b.userId }).expect(404);
    for (const table of ['moment_participants', 'moment_messages', 'moment_reactions', 'moment_knocks', 'moment_invitations']) {
      expect((await db.query(`SELECT count(*)::int AS n FROM ${table}`)).rows[0].n).toBe(0);
    }
    // The Moment row keeps its terminal state rather than vanishing.
    expect((await db.query(`SELECT status FROM moments WHERE id=$1`, [m.id])).rows[0].status).toBe('ENDED');
  });

  it('purges rooms the same way on automatic expiry', async () => {
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(b)).send({ body: 'vanishing' }).expect(201);
    await db.query(`UPDATE moments SET created_at=now()-interval '2 minutes', expires_at=now()-interval '1 minute' WHERE id=$1`, [m.id]);
    await app.get(MomentsClock).tick();
    for (const table of ['moment_participants', 'moment_messages', 'moment_reactions', 'moment_knocks', 'moment_invitations']) {
      expect((await db.query(`SELECT count(*)::int AS n FROM ${table}`)).rows[0].n).toBe(0);
    }
    expect((await db.query(`SELECT status FROM moments WHERE id=$1`, [m.id])).rows[0].status).toBe('EXPIRED');
  });

  it('delivers invitations, chat, reactions and membership through the existing websocket', async () => {
    const disconnect = jest.spyOn(app.get(SignalingGateway), 'handleDisconnect');
    const port = app.getHttpServer().address().port;
    const socket = new WebSocket(`ws://127.0.0.1:${port}/api/v1/signaling/ws?token=${b.accessToken}`);
    const frames: any[] = []; socket.on('message', raw => frames.push(JSON.parse(raw.toString())));
    const waitFor = async (predicate: (f: any) => boolean) => {
      for (let i = 0; i < 200; i++) { if (frames.some(predicate)) return; await new Promise(r => setTimeout(r, 20)); }
      throw new Error('Missing expected realtime frame');
    };
    try {
      await new Promise<void>((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
      await new Promise(r => setTimeout(r, 100));
      const m = await create();
      await http().post(`/api/v1/moments/${m.id}/invites`).set(auth(a)).send({ userId: b.userId }).expect(200);
      await waitFor(f => f.type === 'moment.invited' && f.payload.momentId === m.id);

      await http().post(`/api/v1/moments/${m.id}/join`).set(auth(b)).expect(200);
      await waitFor(f => f.type === 'moment.joined' && f.payload.userId === b.userId);

      const sent = (await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(a)).send({ body: 'welcome in' }).expect(201)).body;
      await waitFor(f => f.type === 'moment.message' && f.payload.message?.id === sent.id);
      expect(frames.find(f => f.type === 'moment.message')?.payload.message.body).toBe('welcome in');

      await http().post(`/api/v1/moments/${m.id}/messages/${sent.id}/react`).set(auth(a)).send({ emoji: '🔥' }).expect(200);
      await waitFor(f => f.type === 'moment.reaction' && f.payload.messageId === sent.id);

      // The leaver is out of the room before the announcement fires, so the
      // remaining room — the host — is who must hear "B left".
      const left = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
      await http().post(`/api/v1/moments/${m.id}/leave`).set(auth(b)).expect(200);
      for (let i = 0; i < 100; i++) {
        if (left.mock.calls.some(c => c[1]?.type === 'moment.left' && c[0] === a.userId)) break;
        await new Promise(r => setTimeout(r, 20));
      }
      expect(left.mock.calls.find(c => c[1]?.type === 'moment.left')?.[0]).toBe(a.userId);
      left.mockRestore();

      await http().delete(`/api/v1/moments/${m.id}`).set(auth(a)).expect(200);
      await waitFor(f => (f.type === 'moment.ended' || f.type === 'moment.expired') && f.payload.momentId === m.id);
    } finally {
      socket.close();
      for (let i = 0; i < 100 && !disconnect.mock.results.length; i++) await new Promise(r => setTimeout(r, 20));
      for (const result of disconnect.mock.results) await result.value;
      disconnect.mockRestore();
    }
  });

  it('comes back for a Moment that was marked over but never cleared up', async () => {
    // Ending a Moment marks it terminal and then erases the room: rows, Redis
    // keys, LiveKit, files. That cannot be one transaction, so a failure part
    // way through used to strand the leftovers for good — the sweep only ever
    // looked at ACTIVE rows, and this one is ENDED.
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/messages`).set(auth(b)).send({ body: 'still here' }).expect(201);

    // Exactly the state a half-finished close leaves behind: terminal, with
    // the room still sitting there and nothing recording that it was tidied.
    await db.query(`UPDATE moments SET status = 'ENDED', cleaned_at = NULL WHERE id = $1`, [m.id]);
    expect((await db.query(`SELECT 1 FROM moment_messages WHERE moment_id = $1`, [m.id])).rowCount).toBe(1);
    expect((await db.query(`SELECT 1 FROM moment_participants WHERE moment_id = $1`, [m.id])).rowCount).toBeGreaterThan(0);

    await app.get(MomentsService).tidy();

    expect((await db.query(`SELECT 1 FROM moment_messages WHERE moment_id = $1`, [m.id])).rowCount).toBe(0);
    expect((await db.query(`SELECT 1 FROM moment_participants WHERE moment_id = $1`, [m.id])).rowCount).toBe(0);
    const [after] = (await db.query(`SELECT cleaned_at FROM moments WHERE id = $1`, [m.id])).rows;
    expect(after.cleaned_at).not.toBeNull();
  });

  it('does not keep re-tidying a Moment it has already finished with', async () => {
    // Otherwise every sweep would walk every Moment ever held, and anyone in
    // one would be told it had ended again each time round.
    const m = await create();
    await join(b, m.id);
    await http().post(`/api/v1/moments/${m.id}/end`).set(auth(a)).expect(200);
    const [closed] = (await db.query(`SELECT cleaned_at FROM moments WHERE id = $1`, [m.id])).rows;
    expect(closed.cleaned_at).not.toBeNull();

    const told = jest.spyOn(app.get(RealtimeRegistry), 'deliverToUser');
    await app.get(MomentsService).tidy();
    expect(told.mock.calls.some(c => c[1]?.type === 'moment.ended')).toBe(false);
    told.mockRestore();
  });
});
