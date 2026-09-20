/**
 * Encrypts message content that was stored before encryption at rest existed.
 *
 * Run inside the API container, once, after MESSAGE_ENCRYPTION_KEY is set:
 *   node dist/scripts/encrypt-backfill.js
 *
 * It is safe to run twice: rows already encrypted are skipped. It is also safe
 * to stop half-way — the app reads both forms, so a half-converted database
 * still works.
 */
import 'reflect-metadata';
import { DataSource } from 'typeorm';
import * as fs from 'fs';
import * as path from 'path';
import { Message } from '../database/entities/message.entity';
import { MediaObject } from '../database/entities/messaging-extras.entity';
import { encryptBuffer, encryptionEnabled, isEncryptedBuffer } from '../common/crypto/field-cipher';

const BATCH = 500;

async function main() {
  if (!encryptionEnabled()) {
    console.error('MESSAGE_ENCRYPTION_KEY is not set — nothing to do, and nothing would be protected.');
    process.exit(1);
  }

  const dataSource = new DataSource({
    type: 'postgres',
    url: process.env.DATABASE_URL,
    entities: [Message, MediaObject],
    synchronize: false,
    logging: false,
  });
  await dataSource.initialize();
  const messages = dataSource.getRepository(Message);

  let converted = 0;
  let scanned = 0;
  // Only rows still in the clear: an encrypted body starts with v1., and
  // encrypted metadata is the JSON string "v1.…".
  for (;;) {
    const rows = await messages
      .createQueryBuilder('m')
      .where("(m.body IS NOT NULL AND m.body NOT LIKE 'v1.%')")
      // Encrypted metadata is the JSON string "v1.… , so its text form starts with "v1.
      .orWhere('(m.metadata IS NOT NULL AND left(m.metadata::text, 4) <> :metaPrefix)', { metaPrefix: '"v1.' })
      .orderBy('m.created_at', 'ASC')
      .take(BATCH)
      .getMany();
    if (rows.length === 0) break;
    scanned += rows.length;
    // Written column by column rather than with save(): save() only writes what
    // it thinks changed, and a row read back through the transformers looks
    // unchanged — which quietly left metadata in the clear.
    for (const row of rows) {
      await messages.update({ id: row.id }, { body: row.body ?? null, metadata: (row.metadata ?? null) as never });
    }
    converted += rows.length;
    console.log(`messages: ${converted} converted`);
    if (rows.length < BATCH) break;
  }

  const media = dataSource.getRepository(MediaObject);
  const dir = process.env.MEDIA_UPLOAD_DIR || path.join(process.cwd(), 'uploads', 'media');
  let files = 0;
  const all = await media.find({ select: ['id', 'fileName'] });
  for (const item of all) {
    const full = path.join(dir, path.basename(item.fileName));
    if (!fs.existsSync(full)) continue;
    const data = fs.readFileSync(full);
    if (isEncryptedBuffer(data)) continue;
    const temp = `${full}.enc`;
    fs.writeFileSync(temp, encryptBuffer(data));
    fs.renameSync(temp, full);
    files++;
    if (files % 25 === 0) console.log(`files: ${files} converted`);
  }

  console.log(`Done. Messages scanned: ${scanned}, files converted: ${files}.`);
  await dataSource.destroy();
}

main().catch((e) => {
  console.error('Backfill failed:', e);
  process.exit(1);
});
