import { parsePhoneNumberFromString, CountryCode } from 'libphonenumber-js';

/**
 * E.164 normalization via libphonenumber-js.
 * @param phone Raw phone input
 * @param defaultRegion ISO 3166-1 alpha-2 default when number has no country code (e.g. ZM)
 */
export function normalizeE164(phone: string, defaultRegion: CountryCode = 'ZM'): string | null {
  const trimmed = phone.trim();
  if (!trimmed) return null;

  try {
    const parsed = parsePhoneNumberFromString(trimmed, defaultRegion);
    if (!parsed || !parsed.isValid()) return null;
    return parsed.format('E.164');
  } catch {
    return null;
  }
}

export function isValidE164(phone: string, defaultRegion: CountryCode = 'ZM'): boolean {
  return normalizeE164(phone, defaultRegion) !== null;
}
