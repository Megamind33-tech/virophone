import { INestApplication } from '@nestjs/common';
import * as request from 'supertest';
import { Client } from 'pg';
import { readFileSync } from 'fs';
import { join } from 'path';
import { createTestApp } from './test-app';
import { OfflineTrustService } from '../../src/offline-trust/offline-trust.service';

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
  const sql1 = readFileSync(join(__dirname, '../../src/database/migrations/001_initial_schema.sql'), 'utf-8');
  const sql2 = readFileSync(join(__dirname, '../../src/database/migrations/002_offline_trust.sql'), 'utf-8');
  await client.query(sql1);
  await client.query(sql2);
  await client.end();
}

describe('Offline Trust Integration', () => {
  let app: INestApplication;
  let pgAvailable = false;

  beforeAll(async () => {
    try {
      const client = new Client({ connectionString: DATABASE_URL });
      await client.connect();
      await client.end();
      pgAvailable = true;
      await resetDatabase();
      app = await createTestApp();
    } catch {
      pgAvailable = false;
    }
  });

  afterAll(async () => {
    if (app) await app.close();
  });

  it('issues trust material for authorized contact', async () => {
    if (!pgAvailable) return;

    const otp1 = await request(app.getHttpServer()).post('/api/v1/auth/otp/request').send({ phoneE164: '+260971100201' });
    const v1 = await request(app.getHttpServer()).post('/api/v1/auth/otp/verify').send({
      challengeId: otp1.body.challengeId, code: '123456', devicePublicKey: 'k1', platform: 'ANDROID', appVersion: '0.1',
    });
    const otp2 = await request(app.getHttpServer()).post('/api/v1/auth/otp/request').send({ phoneE164: '+260971100202' });
    const v2 = await request(app.getHttpServer()).post('/api/v1/auth/otp/verify').send({
      challengeId: otp2.body.challengeId, code: '123456', devicePublicKey: 'k2', platform: 'ANDROID', appVersion: '0.1',
    });

    await request(app.getHttpServer())
      .post('/api/v1/contacts/discover')
      .set('Authorization', `Bearer ${v1.body.accessToken}`)
      .send({ phonesE164: ['+260971100202'] });

    const material = await request(app.getHttpServer())
      .get('/api/v1/offline-trust/material')
      .set('Authorization', `Bearer ${v1.body.accessToken}`);

    expect(material.body.material.length).toBeGreaterThan(0);
    expect(material.body.material[0].peerUserId).toBe(v2.body.userId);
    expect(material.body.material[0].trustToken).toBeTruthy();
  });
});
