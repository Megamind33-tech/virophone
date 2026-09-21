import { Injectable } from '@nestjs/common';
import { createCipheriv, createDecipheriv, randomBytes, randomUUID } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { Readable, Transform } from 'stream';
import { pipeline } from 'stream/promises';
import { decryptText, encryptText } from '../common/crypto/field-cipher';
import { MediaKind } from './playback';

/** What a provider hands back to be played: a byte range of one item. */
export interface MediaRange {
  stream: Readable;
  start: number;
  end: number;
  size: number;
  mime: string;
}

/** A shared item as the provider sees it — a row of moment_media. */
export interface StoredMedia {
  provider: string;
  mime: string;
  size_bytes: string | number;
  file_name: string;
  file_key: string;
}

/**
 * Where something played in a Moment comes from.
 *
 * Only one source exists: media a participant brings from their own phone.
 * Another source — a licensed catalogue, say — would be another provider with
 * its own rights checks, added here; the room, the player and the sync do not
 * change. Nothing here fetches, records or re-streams anyone else's service.
 */
export interface MomentMediaProvider {
  readonly key: string;
  open(item: StoredMedia, from?: number, to?: number): MediaRange;
  remove(item: StoredMedia): void;
}

export const VIDEO_MIME = new Set(['video/mp4', 'video/webm', 'video/3gpp', 'video/quicktime']);
export const AUDIO_MIME = new Set([
  'audio/mpeg', 'audio/mp4', 'audio/m4a', 'audio/x-m4a', 'audio/aac', 'audio/ogg', 'audio/opus',
  'audio/webm', 'audio/flac', 'audio/wav', 'audio/x-wav',
]);
/** Large enough for a short film or an album track on a phone; small enough for a Zambian mobile upload. */
export const MAX_VIDEO_BYTES = 100 * 1024 * 1024;
export const MAX_AUDIO_BYTES = 30 * 1024 * 1024;

export function kindOf(mime: string): MediaKind | null {
  if (VIDEO_MIME.has(mime)) return 'VIDEO';
  if (AUDIO_MIME.has(mime)) return 'AUDIO';
  return null;
}

/**
 * The file really is what it says: the first bytes of each container are
 * fixed. A renamed APK or document is refused before it is stored.
 */
export function looksLike(mime: string, head: Buffer): boolean {
  const ascii = (from: number, to: number) => head.subarray(from, to).toString('latin1');
  const ftyp = head.length >= 8 && ascii(4, 8) === 'ftyp';
  const ebml = head.length >= 4 && head.readUInt32BE(0) === 0x1a45dfa3;
  switch (mime) {
    case 'video/mp4': case 'video/3gpp': case 'video/quicktime':
    case 'audio/mp4': case 'audio/m4a': case 'audio/x-m4a':
      return ftyp || (mime === 'video/quicktime' && ['moov', 'mdat', 'wide', 'free'].includes(ascii(4, 8)));
    case 'video/webm': case 'audio/webm':
      return ebml;
    case 'audio/mpeg':
      return ascii(0, 3) === 'ID3' || (head[0] === 0xff && (head[1] & 0xe0) === 0xe0);
    case 'audio/aac':
      return head[0] === 0xff && (head[1] & 0xf6) === 0xf0;
    case 'audio/ogg': case 'audio/opus':
      return ascii(0, 4) === 'OggS';
    case 'audio/flac':
      return ascii(0, 4) === 'fLaC';
    case 'audio/wav': case 'audio/x-wav':
      return ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WAVE';
    default:
      return false;
  }
}

/** Adds [blocks] to a 128-bit big-endian counter: where AES-CTR stands at a given block. */
function counterAt(iv: Buffer, blocks: number): Buffer {
  const out = Buffer.from(iv);
  let carry = BigInt(blocks);
  for (let i = 15; i >= 0 && carry > 0n; i--) {
    const sum = BigInt(out[i]) + (carry & 0xffn);
    out[i] = Number(sum & 0xffn);
    carry = (carry >> 8n) + (sum >> 8n);
  }
  return out;
}

/** Drops the first [count] bytes that pass through. */
function skip(count: number): Transform {
  let left = count;
  return new Transform({
    transform(chunk: Buffer, _enc, done) {
      if (left >= chunk.length) { left -= chunk.length; return done(); }
      const rest = left > 0 ? chunk.subarray(left) : chunk;
      left = 0;
      done(null, rest);
    },
  });
}

/**
 * Media people bring themselves. Encrypted on disk with a key of its own
 * (AES-256-CTR, so any part can be read without the rest — seeking in a film
 * must not mean decrypting all of it). The key is kept in the database row,
 * wrapped with the server's at-rest key when one is configured.
 */
@Injectable()
export class UploadedMediaProvider implements MomentMediaProvider {
  readonly key = 'UPLOAD';

  dir(): string {
    const d = process.env.MOMENT_MEDIA_DIR || path.join(process.cwd(), 'uploads', 'moment-media');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  /** Where an upload waits before it is checked and kept. */
  incomingDir(): string {
    const d = path.join(this.dir(), 'incoming');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  /** Encrypts a checked upload into place and removes the plain copy. */
  async keep(plainPath: string): Promise<{ fileName: string; fileKey: string }> {
    const key = randomBytes(32);
    const iv = randomBytes(16);
    const fileName = `${randomUUID()}.bin`;
    try {
      await pipeline(
        fs.createReadStream(plainPath),
        createCipheriv('aes-256-ctr', key, iv),
        fs.createWriteStream(path.join(this.dir(), fileName), { flags: 'wx' }),
      );
    } finally {
      fs.rmSync(plainPath, { force: true });
    }
    return { fileName, fileKey: encryptText(Buffer.concat([key, iv]).toString('base64')) };
  }

  private pathFor(fileName: string): string | null {
    const safe = path.basename(fileName);
    if (!/^[0-9a-f-]{36}\.bin$/i.test(safe)) return null;
    return path.join(this.dir(), safe);
  }

  open(item: StoredMedia, from = 0, to?: number): MediaRange {
    const full = this.pathFor(item.file_name);
    if (!full || !fs.existsSync(full)) throw new Error('Media file missing.');
    const size = Number(item.size_bytes);
    const start = Math.max(0, Math.min(from, size - 1));
    const end = Math.max(start, Math.min(to ?? size - 1, size - 1));
    const secret = Buffer.from(decryptText(item.file_key), 'base64');
    const key = secret.subarray(0, 32);
    const iv = secret.subarray(32, 48);
    const block = Math.floor(start / 16);
    const decipher = createDecipheriv('aes-256-ctr', key, counterAt(iv, block));
    const raw = fs.createReadStream(full, { start: block * 16, end });
    const stream = raw.pipe(decipher).pipe(skip(start - block * 16));
    raw.on('error', (e) => stream.destroy(e));
    return { stream, start, end, size, mime: item.mime };
  }

  remove(item: StoredMedia): void {
    const full = this.pathFor(item.file_name);
    if (full) fs.rmSync(full, { force: true });
  }
}
