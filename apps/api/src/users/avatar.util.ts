import * as fs from 'fs';
import * as path from 'path';

const ALLOWED_MIME = new Set(['image/jpeg', 'image/png', 'image/webp']);

export function avatarUploadDir(): string {
  return process.env.AVATAR_UPLOAD_DIR || path.join(process.cwd(), 'uploads', 'avatars');
}

/** The address phones reach this server on, with no trailing slash. */
export function publicApiBaseUrl(): string {
  return (
    process.env.API_PUBLIC_URL ||
    process.env.API_BASE_URL ||
    `http://localhost:${process.env.API_PORT || 3001}`
  ).replace(/\/$/, '');
}

export function publicAvatarBaseUrl(): string {
  // API_BASE_URL is the one the VPS compose file actually sets; API_PUBLIC_URL
  // was never passed into the container, so production built every avatar URL
  // on http://localhost:3001 — which a phone resolves to itself, so no uploaded
  // photo could ever be displayed.
  const base = (
    process.env.API_PUBLIC_URL ||
    process.env.API_BASE_URL ||
    `http://localhost:${process.env.API_PORT || 3001}`
  ).replace(/\/$/, '');
  return `${base}/api/v1/media/avatars`;
}

const STORED_AVATAR = /\/api\/v1\/media\/avatars\/([0-9a-f-]{36}\.(?:jpg|png|webp))(\?[^#]*)?$/i;

/**
 * The URL to hand a client for a stored avatar.
 *
 * Our own avatars are rebuilt on the current public base rather than returned
 * as stored, so rows written with a wrong host (every one written before the
 * fix above) repair themselves on read. Anything else is returned untouched.
 */
export function publicAvatarUrl(stored: string | null | undefined): string | null {
  if (!stored || !stored.trim()) return null;
  const m = STORED_AVATAR.exec(stored.trim());
  if (!m) return stored;
  return `${publicAvatarBaseUrl()}/${m[1]}${m[2] || ''}`;
}

export function validateAvatarMime(mimetype: string): void {
  if (!ALLOWED_MIME.has(mimetype)) {
    throw new Error('INVALID_AVATAR_TYPE');
  }
}

export function extensionForMime(mimetype: string): string {
  switch (mimetype) {
    case 'image/jpeg':
      return '.jpg';
    case 'image/png':
      return '.png';
    case 'image/webp':
      return '.webp';
    default:
      return '.jpg';
  }
}

export function ensureAvatarDir(): string {
  const dir = avatarUploadDir();
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

export function avatarFilePath(userId: string, ext: string): string {
  return path.join(ensureAvatarDir(), `${userId}${ext}`);
}

export function readAvatarFile(fileName: string): { buffer: Buffer; contentType: string } | null {
  const safeName = path.basename(fileName);
  if (!/^[0-9a-f-]{36}\.(jpg|png|webp)$/i.test(safeName)) {
    return null;
  }
  const fullPath = path.join(avatarUploadDir(), safeName);
  if (!fs.existsSync(fullPath)) {
    return null;
  }
  const ext = path.extname(safeName).toLowerCase();
  const contentType = ext === '.png' ? 'image/png' : ext === '.webp' ? 'image/webp' : 'image/jpeg';
  return { buffer: fs.readFileSync(fullPath), contentType };
}
