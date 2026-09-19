import { validateProductionConfig } from './production-config';

describe('Production fail-closed config', () => {
  const originalEnv = process.env;

  beforeEach(() => {
    process.env = { ...originalEnv };
    // The shell/.env the suite inherits sets OTP_PROVIDER, which leaked
    // into the "proper secrets" case and made it fail for a reason that
    // test is not about. Each case sets what it needs explicitly.
    delete process.env.OTP_PROVIDER;
  });

  afterAll(() => {
    process.env = originalEnv;
  });

  it('allows development without all secrets', () => {
    process.env.NODE_ENV = 'development';
    expect(() => validateProductionConfig()).not.toThrow();
  });

  it('refuses production without required secrets', () => {
    process.env.NODE_ENV = 'production';
    delete process.env.DATABASE_URL;
    expect(() => validateProductionConfig()).toThrow(/missing required/);
  });

  it('refuses production with dev fallback secrets', () => {
    process.env.NODE_ENV = 'production';
    process.env.DATABASE_URL = 'postgresql://u:p@localhost/db';
    process.env.REDIS_URL = 'redis://localhost';
    process.env.JWT_ACCESS_SECRET = 'dev_access_secret_change_in_production';
    process.env.JWT_REFRESH_SECRET = 'x'.repeat(32);
    process.env.CONTACT_HASH_SALT = 'x'.repeat(32);
    process.env.TURN_SECRET = 'x'.repeat(32);
    process.env.EPHEMERAL_SIGNING_SECRET = 'x'.repeat(32);
    expect(() => validateProductionConfig()).toThrow(/development fallback/);
  });

  it('allows production with proper secrets', () => {
    process.env.NODE_ENV = 'production';
    process.env.DATABASE_URL = 'postgresql://u:p@localhost/db';
    process.env.REDIS_URL = 'redis://localhost';
    process.env.JWT_ACCESS_SECRET = 'a'.repeat(32);
    process.env.JWT_REFRESH_SECRET = 'b'.repeat(32);
    process.env.CONTACT_HASH_SALT = 'c'.repeat(32);
    process.env.TURN_SECRET = 'd'.repeat(32);
    process.env.EPHEMERAL_SIGNING_SECRET = 'e'.repeat(32);
    process.env.LIVEKIT_API_KEY = 'viroabc123';
    process.env.LIVEKIT_API_SECRET = 'f'.repeat(40);
    process.env.LIVEKIT_URL = 'wss://example.test/livekit-rtc';
    expect(() => validateProductionConfig()).not.toThrow();
  });

  it('refuses production OTP_PROVIDER=test', () => {
    process.env.NODE_ENV = 'production';
    process.env.DATABASE_URL = 'postgresql://u:p@localhost/db';
    process.env.REDIS_URL = 'redis://localhost';
    process.env.JWT_ACCESS_SECRET = 'a'.repeat(32);
    process.env.JWT_REFRESH_SECRET = 'b'.repeat(32);
    process.env.CONTACT_HASH_SALT = 'c'.repeat(32);
    process.env.TURN_SECRET = 'd'.repeat(32);
    process.env.EPHEMERAL_SIGNING_SECRET = 'e'.repeat(32);
    process.env.LIVEKIT_API_KEY = 'viroabc123';
    process.env.LIVEKIT_API_SECRET = 'f'.repeat(40);
    process.env.LIVEKIT_URL = 'wss://example.test/livekit-rtc';
    process.env.OTP_PROVIDER = 'test';
    expect(() => validateProductionConfig()).toThrow(/OTP_PROVIDER=test is forbidden/);
  });

  it('refuses production hardware-test with wildcard allowlist', () => {
    process.env.NODE_ENV = 'production';
    process.env.DATABASE_URL = 'postgresql://u:p@localhost/db';
    process.env.REDIS_URL = 'redis://localhost';
    process.env.JWT_ACCESS_SECRET = 'a'.repeat(32);
    process.env.JWT_REFRESH_SECRET = 'b'.repeat(32);
    process.env.CONTACT_HASH_SALT = 'c'.repeat(32);
    process.env.TURN_SECRET = 'd'.repeat(32);
    process.env.EPHEMERAL_SIGNING_SECRET = 'e'.repeat(32);
    process.env.LIVEKIT_API_KEY = 'viroabc123';
    process.env.LIVEKIT_API_SECRET = 'f'.repeat(40);
    process.env.LIVEKIT_URL = 'wss://example.test/livekit-rtc';
    process.env.OTP_PROVIDER = 'hardware-test';
    process.env.HARDWARE_TEST_PHONES_E164 = '*';
    process.env.HARDWARE_TEST_OTP_CODE = 'Hw7k9m';
    expect(() => validateProductionConfig()).toThrow(/Unsafe hardware-test allowlist/);
  });

  it('allows production hardware-test with explicit allowlist and credential', () => {
    process.env.NODE_ENV = 'production';
    process.env.DATABASE_URL = 'postgresql://u:p@localhost/db';
    process.env.REDIS_URL = 'redis://localhost';
    process.env.JWT_ACCESS_SECRET = 'a'.repeat(32);
    process.env.JWT_REFRESH_SECRET = 'b'.repeat(32);
    process.env.CONTACT_HASH_SALT = 'c'.repeat(32);
    process.env.TURN_SECRET = 'd'.repeat(32);
    process.env.EPHEMERAL_SIGNING_SECRET = 'e'.repeat(32);
    process.env.LIVEKIT_API_KEY = 'viroabc123';
    process.env.LIVEKIT_API_SECRET = 'f'.repeat(40);
    process.env.LIVEKIT_URL = 'wss://example.test/livekit-rtc';
    process.env.OTP_PROVIDER = 'hardware-test';
    process.env.HARDWARE_TEST_PHONES_E164 = '+260961582985,+260977426940';
    process.env.HARDWARE_TEST_OTP_CODE = 'Hw7k9m';
    expect(() => validateProductionConfig()).not.toThrow();
  });
});
