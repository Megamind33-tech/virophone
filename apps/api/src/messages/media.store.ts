import { Injectable } from '@nestjs/common';
import * as fs from 'fs';
import * as path from 'path';
import { randomUUID } from 'crypto';

const EXT: Record<string, string> = {
  'audio/mp4': '.m4a',
  'audio/m4a': '.m4a',
  'audio/x-m4a': '.m4a',
  'audio/aac': '.aac',
  'audio/ogg': '.ogg',
  'audio/opus': '.ogg',
  'audio/webm': '.webm',
  'audio/mpeg': '.mp3',
  'audio/3gpp': '.3gp',
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'image/gif': '.gif',
};

/**
 * Documents people actually send each other. Anything installable or
 * executable is refused — a chat app must not be a delivery route for APKs.
 */
export const ALLOWED_FILE_MIME = new Set([
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/vnd.oasis.opendocument.text',
  'application/vnd.oasis.opendocument.spreadsheet',
  'application/vnd.oasis.opendocument.presentation',
  'application/rtf',
  'application/zip',
  'text/plain',
  'text/csv',
  'text/calendar',
  'application/json',
  'image/heic',
  'image/heif',
  'image/tiff',
  'image/svg+xml',
  'video/mp4',
  'video/3gpp',
  'video/quicktime',
  'video/webm',
  'audio/wav',
  'audio/x-wav',
  'audio/flac',
]);

export const ALLOWED_VOICE_MIME = new Set(Object.keys(EXT).filter((m) => m.startsWith('audio/')));
export const ALLOWED_IMAGE_MIME = new Set(Object.keys(EXT).filter((m) => m.startsWith('image/')));

/**
 * Voice-note files on disk. Kept apart from avatars: avatars are public by
 * design, these are served only through an authorised endpoint.
 */
@Injectable()
export class MediaStore {
  private dir(): string {
    const d = process.env.MEDIA_UPLOAD_DIR || path.join(process.cwd(), 'uploads', 'media');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  save(buffer: Buffer, mime: string): string {
    const name = `${randomUUID()}${EXT[mime] ?? '.bin'}`;
    fs.writeFileSync(path.join(this.dir(), name), buffer);
    return name;
  }

  pathFor(fileName: string): string | null {
    const safe = path.basename(fileName);
    if (!/^[0-9a-f-]{36}\.[a-z0-9]{2,4}$/i.test(safe)) return null;
    const full = path.join(this.dir(), safe);
    return fs.existsSync(full) ? full : null;
  }

  remove(fileName: string): void {
    const full = this.pathFor(fileName);
    if (full) {
      try {
        fs.unlinkSync(full);
      } catch {
        /* already gone */
      }
    }
  }
}
