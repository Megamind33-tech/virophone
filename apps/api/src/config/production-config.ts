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

export function validateProductionConfig(): void {
  const nodeEnv = process.env.NODE_ENV || 'development';
  if (nodeEnv !== 'production') {
    if (nodeEnv === 'development' || nodeEnv === 'test') {
      console.warn('DEVELOPMENT CONFIGURATION — NOT FOR PRODUCTION');
    }
    return;
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
}
