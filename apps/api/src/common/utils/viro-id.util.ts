/**
 * Viro ID normalization: lowercase, strip leading @
 */
export function normalizeViroId(viroId: string): string | null {
  const trimmed = viroId.trim();
  if (!trimmed) return null;

  const withoutAt = trimmed.startsWith('@') ? trimmed.slice(1) : trimmed;
  const normalized = withoutAt.toLowerCase();

  // Rules: alphanumeric, dots, underscores, 3-30 chars
  if (!/^[a-z0-9][a-z0-9._]{2,29}$/.test(normalized)) {
    return null;
  }
  return normalized;
}

export function formatViroId(normalized: string): string {
  return `@${normalized}`;
}
