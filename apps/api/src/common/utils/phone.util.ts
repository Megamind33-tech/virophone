/**
 * E.164 phone number normalization and validation.
 * Phase 0: basic validation. Production should use libphonenumber.
 */
export function normalizeE164(phone: string): string | null {
  const cleaned = phone.replace(/[\s\-\(\)\.]/g, '');
  if (!cleaned.startsWith('+')) {
    return null;
  }
  const digits = cleaned.slice(1);
  if (!/^\d{7,15}$/.test(digits)) {
    return null;
  }
  return `+${digits}`;
}

export function isValidE164(phone: string): boolean {
  return normalizeE164(phone) !== null;
}
