import { createCipheriv, createDecipheriv, randomBytes } from 'crypto';

/**
 * Encryption at rest for message content.
 *
 * This is NOT end-to-end encryption: the server holds the key and can read
 * what it stores. What it protects against is the likeliest real leak — a
 * dumped database, a stolen backup, a snapshot left somewhere — because the
 * key lives in the environment rather than in Postgres.
 *
 * Format: v1.<iv>.<tag>.<ciphertext>, each part base64. Anything without the
 * v1. prefix is read back as-is, so rows written before this existed still
 * open, and a half-migrated database is not a broken one.
 */
const PREFIX = 'v1.';
const IV_BYTES = 12;
const KEY_BYTES = 32;

let cachedKey: Buffer | null | undefined;

/** The key from MESSAGE_ENCRYPTION_KEY (base64, 32 bytes), or null when unset. */
export function encryptionKey(): Buffer | null {
  if (cachedKey !== undefined) return cachedKey;
  const raw = (process.env.MESSAGE_ENCRYPTION_KEY || '').trim();
  if (!raw) {
    cachedKey = null;
    return cachedKey;
  }
  const key = Buffer.from(raw, 'base64');
  if (key.length !== KEY_BYTES) {
    throw new Error('MESSAGE_ENCRYPTION_KEY must be 32 bytes, base64 encoded (generate: openssl rand -base64 32)');
  }
  cachedKey = key;
  return cachedKey;
}

/** Tests and key rotation change the key at runtime. */
export function resetEncryptionKeyCache(): void {
  cachedKey = undefined;
}

export function encryptionEnabled(): boolean {
  return encryptionKey() !== null;
}

/** True for a value this module wrote. */
export function isEncrypted(value: unknown): boolean {
  return typeof value === 'string' && value.startsWith(PREFIX);
}

/** Encrypts text. Without a key configured the text is returned unchanged. */
export function encryptText(plain: string): string {
  const key = encryptionKey();
  if (!key) return plain;
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  return PREFIX + [iv.toString('base64'), cipher.getAuthTag().toString('base64'), body.toString('base64')].join('.');
}

/**
 * Decrypts what this module wrote. Plain text from before encryption was
 * switched on passes straight through.
 */
export function decryptText(stored: string): string {
  if (!isEncrypted(stored)) return stored;
  const key = encryptionKey();
  if (!key) {
    throw new Error('Stored message is encrypted but MESSAGE_ENCRYPTION_KEY is not set.');
  }
  const [, iv, tag, body] = stored.split('.');
  if (!iv || !tag || !body) throw new Error('Encrypted value is malformed.');
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64'));
  decipher.setAuthTag(Buffer.from(tag, 'base64'));
  return Buffer.concat([decipher.update(Buffer.from(body, 'base64')), decipher.final()]).toString('utf8');
}

/** A TypeORM transformer for a text column. */
export const encryptedTextColumn = {
  to: (value: string | null | undefined): string | null =>
    value === null || value === undefined ? (value ?? null) : encryptText(value),
  from: (value: string | null): string | null =>
    value === null || value === undefined ? null : decryptText(value),
};

/**
 * A TypeORM transformer for a jsonb column. The encrypted form is a JSON
 * string, which jsonb stores happily — but it can no longer be queried with
 * ->> or @>, so anything the server needs to search on has its own column or
 * table (see message_mentions).
 */
export const encryptedJsonColumn = {
  to: (value: unknown): unknown => {
    if (value === null || value === undefined) return value ?? null;
    if (!encryptionEnabled()) return value;
    return encryptText(JSON.stringify(value));
  },
  from: (value: unknown): unknown => {
    if (!isEncrypted(value)) return value ?? null;
    return JSON.parse(decryptText(value as string));
  },
};

// ------------------------------------------------------------------- files

/** Files carry their own header so a plain file from before is still readable. */
const FILE_MAGIC = Buffer.from('VIRO1');

/** iv || ciphertext || tag, behind a magic header. */
export function encryptBuffer(plain: Buffer): Buffer {
  const key = encryptionKey();
  if (!key) return plain;
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([cipher.update(plain), cipher.final()]);
  return Buffer.concat([FILE_MAGIC, iv, body, cipher.getAuthTag()]);
}

export function isEncryptedBuffer(data: Buffer): boolean {
  return data.length > FILE_MAGIC.length && data.subarray(0, FILE_MAGIC.length).equals(FILE_MAGIC);
}

export function decryptBuffer(stored: Buffer): Buffer {
  if (!isEncryptedBuffer(stored)) return stored;
  const key = encryptionKey();
  if (!key) {
    throw new Error('Stored file is encrypted but MESSAGE_ENCRYPTION_KEY is not set.');
  }
  const iv = stored.subarray(FILE_MAGIC.length, FILE_MAGIC.length + IV_BYTES);
  const tag = stored.subarray(stored.length - 16);
  const body = stored.subarray(FILE_MAGIC.length + IV_BYTES, stored.length - 16);
  const decipher = createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(body), decipher.final()]);
}
