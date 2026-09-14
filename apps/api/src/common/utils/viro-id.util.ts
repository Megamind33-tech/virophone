/**
 * Canonical Viro ID rules (V1):
 * - Charset: a-z, 0-9, dot, underscore only (ASCII)
 * - Length: 3–30 characters after normalization
 * - Must start with alphanumeric
 * - No Unicode / homoglyph identifiers
 * - Case-insensitive; stored lowercase without @
 */

const VIRO_ID_PATTERN = /^[a-z0-9][a-z0-9._]{2,29}$/;
const MAX_RAW_LENGTH = 64;

export function normalizeViroId(viroId: string): string | null {
  if (!viroId || viroId.length > MAX_RAW_LENGTH) return null;

  // Reject non-ASCII (Unicode lookalikes, homoglyphs)
  if (!/^[\x00-\x7F]*$/.test(viroId)) return null;

  const trimmed = viroId.trim();
  if (!trimmed) return null;

  const withoutAt = trimmed.startsWith('@') ? trimmed.slice(1) : trimmed;
  const normalized = withoutAt.toLowerCase();

  if (!VIRO_ID_PATTERN.test(normalized)) {
    return null;
  }

  // Reject consecutive dots, leading/trailing dots
  if (normalized.includes('..') || normalized.startsWith('.') || normalized.endsWith('.')) {
    return null;
  }

  return normalized;
}

export function formatViroId(normalized: string): string {
  return `@${normalized}`;
}

export const VIRO_ID_RULES = {
  charset: 'a-z, 0-9, . (dot), _ (underscore)',
  minLength: 3,
  maxLength: 30,
  asciiOnly: true,
};
