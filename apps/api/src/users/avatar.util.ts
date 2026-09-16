import * as fs from 'fs';
import * as path from 'path';

const ALLOWED_MIME = new Set(['image/jpeg', 'image/png', 'image/webp']);

export function avatarUploadDir(): string {
  return process.env.AVATAR_UPLOAD_DIR || path.join(process.cwd(), 'uploads', 'avatars');
}

export function publicAvatarBaseUrl(): string {
  const base = (process.env.API_PUBLIC_URL || `http://localhost:${process.env.API_PORT || 3001}`).replace(/\/$/, '');
  return `${base}/api/v1/media/avatars`;
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
