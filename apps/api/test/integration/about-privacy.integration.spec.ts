/**
 * End-to-end: the About line, and who may see a photo, an About line and a
 * last seen — including the rule that hiding your own last seen hides
 * everyone else's from you.
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

describe('About line and profile privacy', () => {
  let app: INestApplication;
  let available = false;
  let amina: User; // has a photo and an About line
  let contact: User; // holds Amina's number
  let stranger: User;
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

  const profileOf = async (viewer: User, owner: User) =>
    (await http().get(`/api/v1/me/profile/${owner.userId}`).set(as(viewer)).expect(200)).body;

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
    amina = await register('+260978600001', 'Amina');
    contact = await register('+260978600002', 'Contact');
    stranger = await register('+260978600003', 'Stranger');
    // A photo on file, and someone holding Amina's number.
    await sql('UPDATE profiles SET avatar_url = $2 WHERE user_id = $1', [
      amina.userId,
      'http://localhost:3001/api/v1/media/avatars/11111111-1111-1111-1111-111111111111.jpg',
    ]);
    await http().post('/api/v1/contacts/discover').set(as(contact))
      .send({ phonesE164: ['+260978600001'], region: 'ZM' });
  }, 120_000);

  afterAll(async () => {
    if (app) await app.close();
  });

  const skip = () => {
    if (!available) console.warn('Skipping: PostgreSQL unavailable');
    return !available;
  };

  it('keeps an About line, tidied and capped', async () => {
    if (skip()) return;
    const me = await http().get('/api/v1/me').set(as(amina)).expect(200);
    expect(me.body.about).toBeNull();
    expect(me.body.aboutVisibility).toBe('EVERYONE');
    expect(me.body.lastSeenVisibility).toBe('CONTACTS');

    const saved = await http().patch('/api/v1/me').set(as(amina))
      .send({ about: '  At work   until 5  ' }).expect(200);
    expect(saved.body.about).toBe('At work until 5');

    await http().patch('/api/v1/me').set(as(amina)).send({ about: 'x'.repeat(200) }).expect(400);
    // Clearing it is allowed.
    expect((await http().patch('/api/v1/me').set(as(amina)).send({ about: '' }).expect(200)).body.about).toBeNull();
    await http().patch('/api/v1/me').set(as(amina)).send({ about: 'At work until 5' }).expect(200);
  });

  it('refuses a privacy setting that is not one of the three', async () => {
    if (skip()) return;
    await http().patch('/api/v1/me').set(as(amina)).send({ photoVisibility: 'FRIENDS_OF_FRIENDS' }).expect(400);
  });

  it('shows the photo and About to everyone by default', async () => {
    if (skip()) return;
    const seen = await profileOf(stranger, amina);
    expect(seen.avatarUrl).toContain('/media/avatars/');
    expect(seen.about).toBe('At work until 5');
    expect(seen.displayName).toBe('Amina');
  });

  it('"my contacts" means the people who can already reach me', async () => {
    if (skip()) return;
    await http().patch('/api/v1/me').set(as(amina))
      .send({ photoVisibility: 'CONTACTS', aboutVisibility: 'CONTACTS' }).expect(200);

    const byContact = await profileOf(contact, amina);
    expect(byContact.avatarUrl).toContain('/media/avatars/');
    expect(byContact.about).toBe('At work until 5');

    const byStranger = await profileOf(stranger, amina);
    expect(byStranger.avatarUrl).toBeNull();
    expect(byStranger.about).toBeNull();
    // The name is still there: it is how they are addressed.
    expect(byStranger.displayName).toBe('Amina');
  });

  it('"nobody" hides it from everyone, and still shows it to me', async () => {
    if (skip()) return;
    await http().patch('/api/v1/me').set(as(amina)).send({ photoVisibility: 'NOBODY' }).expect(200);
    expect((await profileOf(contact, amina)).avatarUrl).toBeNull();
    // My own profile is unaffected.
    expect((await http().get('/api/v1/me').set(as(amina)).expect(200)).body.avatarUrl).toContain('/media/avatars/');
    expect((await profileOf(amina, amina)).avatarUrl).toContain('/media/avatars/');
  });

  it('hides the photo from contact discovery too', async () => {
    if (skip()) return;
    const found = await http().post('/api/v1/contacts/discover').set(as(contact))
      .send({ phonesE164: ['+260978600001'], region: 'ZM' }).expect(201);
    expect(found.body.matches[0].userId).toBe(amina.userId);
    expect(found.body.matches[0].avatarUrl).toBeNull();

    await http().patch('/api/v1/me').set(as(amina)).send({ photoVisibility: 'EVERYONE' }).expect(200);
    const again = await http().post('/api/v1/contacts/discover').set(as(contact))
      .send({ phonesE164: ['+260978600001'], region: 'ZM' }).expect(201);
    expect(again.body.matches[0].avatarUrl).toContain('/media/avatars/');
  });

  it('shares last seen with contacts, not with strangers', async () => {
    if (skip()) return;
    await http().post('/api/v1/presence').set(as(amina)).send({ state: 'ONLINE' });

    const forContact = await http().get(`/api/v1/presence/${amina.userId}`).set(as(contact)).expect(200);
    expect(forContact.body.lastSeenAt).toBeTruthy();

    const forStranger = await http().get(`/api/v1/presence/${amina.userId}`).set(as(stranger)).expect(200);
    expect(forStranger.body.lastSeenAt).toBeNull();
    expect(forStranger.body.state).toBe('OFFLINE');
  });

  it('hiding my own last seen hides everyone else\'s from me', async () => {
    if (skip()) return;
    await http().patch('/api/v1/me').set(as(amina)).send({ lastSeenVisibility: 'EVERYONE' }).expect(200);
    await http().post('/api/v1/presence').set(as(amina)).send({ state: 'ONLINE' });
    expect((await http().get(`/api/v1/presence/${amina.userId}`).set(as(stranger)).expect(200)).body.lastSeenAt).toBeTruthy();

    // The stranger now hides theirs — and loses the view of Amina's.
    await http().patch('/api/v1/me').set(as(stranger)).send({ lastSeenVisibility: 'NOBODY' }).expect(200);
    expect((await http().get(`/api/v1/presence/${amina.userId}`).set(as(stranger)).expect(200)).body.lastSeenAt).toBeNull();
    // Amina, who shares hers, still sees her own.
    expect((await http().get(`/api/v1/presence/${amina.userId}`).set(as(amina)).expect(200)).body.state).toBeTruthy();
  });
});
