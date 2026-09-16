import { normalizeE164, isValidE164 } from '../../common/utils/phone.util';

const UNSAFE_ALLOWLIST_TOKENS = new Set(['*', 'ALL', 'ANY', 'EVERY', 'WILDCARD']);
const WEAK_CREDENTIAL_PATTERNS = [
  /^(\d)\1{5,}$/,
  /^123456$/,
  /^654321$/,
  /^000000$/,
];

export function parseHardwareTestAllowlistRaw(raw: string | undefined): string[] {
  return (raw || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function normalizeHardwareTestAllowlist(raw: string | undefined): string[] {
  const entries = parseHardwareTestAllowlistRaw(raw);
  for (const entry of entries) {
    if (UNSAFE_ALLOWLIST_TOKENS.has(entry.toUpperCase())) {
      throw new Error(`Unsafe hardware-test allowlist token: ${entry}`);
    }
  }
  const normalized = entries.map((entry) => {
    const phone = normalizeE164(entry);
    if (!phone || !isValidE164(phone)) {
      throw new Error(`Invalid hardware-test allowlist E.164: ${entry}`);
    }
    return phone;
  });
  if (new Set(normalized).size !== normalized.length) {
    throw new Error('Hardware-test allowlist contains duplicate phone numbers.');
  }
  return normalized;
}

export function validateHardwareTestCredential(code: string | undefined): void {
  if (!code || code.length < 6 || code.length > 32) {
    throw new Error(
      'Hardware-test credential must be 6–32 characters (set HARDWARE_TEST_OTP_CODE on VPS only).',
    );
  }
  if (!/^[A-Za-z0-9]+$/.test(code)) {
    throw new Error('Hardware-test credential must be alphanumeric.');
  }
  for (const pattern of WEAK_CREDENTIAL_PATTERNS) {
    if (pattern.test(code)) {
      throw new Error('Hardware-test credential is too weak for production.');
    }
  }
}

export function validateHardwareTestProductionConfig(env: NodeJS.ProcessEnv = process.env): void {
  if (env.OTP_PROVIDER !== 'hardware-test') {
    return;
  }
  const allowlist = normalizeHardwareTestAllowlist(env.HARDWARE_TEST_PHONES_E164);
  if (allowlist.length === 0) {
    throw new Error(
      'Production startup refused: OTP_PROVIDER=hardware-test requires a non-empty HARDWARE_TEST_PHONES_E164 allowlist.',
    );
  }
  validateHardwareTestCredential(env.HARDWARE_TEST_OTP_CODE);
}

export function isHardwareTestMode(env: NodeJS.ProcessEnv = process.env): boolean {
  return env.OTP_PROVIDER === 'hardware-test';
}

export function isPhoneHardwareTestAllowed(
  normalizedPhone: string,
  env: NodeJS.ProcessEnv = process.env,
): boolean {
  if (!isHardwareTestMode(env)) {
    return false;
  }
  try {
    const allowlist = normalizeHardwareTestAllowlist(env.HARDWARE_TEST_PHONES_E164);
    return allowlist.includes(normalizedPhone);
  } catch {
    return false;
  }
}

export function getHardwareTestOtpCode(env: NodeJS.ProcessEnv = process.env): string {
  validateHardwareTestCredential(env.HARDWARE_TEST_OTP_CODE);
  return env.HARDWARE_TEST_OTP_CODE!;
}

export function maskPhoneForSecurityLog(phoneE164: string): string {
  if (phoneE164.length <= 6) return '****';
  return `${phoneE164.slice(0, 4)}****${phoneE164.slice(-2)}`;
}
