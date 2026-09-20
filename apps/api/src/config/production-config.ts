import { validateHardwareTestProductionConfig } from '../auth/otp/hardware-test.config';

/**
 * Production fail-closed configuration validation.
 * Application MUST NOT start in production without required secrets.
 */
const REQUIRED_IN_PRODUCTION = [
  'DATABASE_URL',
  'REDIS_URL',
  'JWT_ACCESS_SECRET',
  'JWT_REFRESH_SECRET',
  'CONTACT_HASH_SALT',
  'TURN_SECRET',
  'EPHEMERAL_SIGNING_SECRET',
  // Internet call media runs entirely on LiveKit. With any of these
  // missing the API starts and reports healthy, calls ring, connect and
  // end normally, and carry no audio at all — the join-token endpoint is
  // the only thing that fails, and only at call time. Fail closed here
  // instead, where it is visible at deploy.
  'LIVEKIT_API_KEY',
  'LIVEKIT_API_SECRET',
  'LIVEKIT_URL',
] as const;

const DEV_FALLBACK_PATTERNS = [
  /^dev_/,
  /change_me/,
  /dev_password/,
  /dev_access_secret/,
  /dev_refresh_secret/,
  /dev_contact_salt/,
  /dev_turn_secret/,
];

/**
 * Message content is encrypted at rest, so production must have the key. A
 * server that starts without it would either write readable messages or fail
 * to open the ones it has — both worse than refusing to start.
 */
const ENCRYPTION_KEY_VAR = 'MESSAGE_ENCRYPTION_KEY';

export function validateProductionConfig(): void {
  const nodeEnv = process.env.NODE_ENV || 'development';
  if (nodeEnv !== 'production') {
    if (nodeEnv === 'development' || nodeEnv === 'test') {
      console.warn('DEVELOPMENT CONFIGURATION — NOT FOR PRODUCTION');
    }
    return;
  }

  const encryptionKey = (process.env[ENCRYPTION_KEY_VAR] || '').trim();
  if (!encryptionKey) {
    throw new Error(
      `Production startup refused: ${ENCRYPTION_KEY_VAR} is not set. ` +
        'Message content is encrypted at rest and cannot be read without it. ' +
        'Generate one with: openssl rand -base64 32 — and keep a copy somewhere safe, ' +
        'because losing it means losing every encrypted message.',
    );
  }
  if (Buffer.from(encryptionKey, 'base64').length !== 32) {
    throw new Error(`Production startup refused: ${ENCRYPTION_KEY_VAR} must be 32 bytes, base64 encoded.`);
  }

  const missing: string[] = [];
  for (const key of REQUIRED_IN_PRODUCTION) {
    const value = process.env[key];
    if (!value || value.trim() === '') {
      missing.push(key);
      continue;
    }
    for (const pattern of DEV_FALLBACK_PATTERNS) {
      if (pattern.test(value)) {
        throw new Error(
          `Production startup refused: ${key} contains development fallback value.`,
        );
      }
    }
  }

  if (missing.length > 0) {
    throw new Error(
      `Production startup refused: missing required environment variables: ${missing.join(', ')}`,
    );
  }

  if ((process.env.JWT_ACCESS_SECRET || '').length < 32) {
    throw new Error('Production startup refused: JWT_ACCESS_SECRET too short (min 32 chars).');
  }

  if (process.env.OTP_PROVIDER === 'test') {
    throw new Error(
      'Production startup refused: OTP_PROVIDER=test is forbidden in production.',
    );
  }

  if (process.env.OTP_PROVIDER === 'email') {
    const required = ['SMTP_HOST', 'OTP_EMAIL_FROM', 'OTP_DELIVERY_EMAIL'];
    const missingEmail = required.filter((k) => !(process.env[k] || '').trim());
    if (missingEmail.length > 0) {
      throw new Error(
        `Production startup refused: OTP_PROVIDER=email requires ${missingEmail.join(', ')}.`,
      );
    }
  }

  if (process.env.OTP_PROVIDER === 'hardware-test') {
    validateHardwareTestProductionConfig(process.env);
  }
}
