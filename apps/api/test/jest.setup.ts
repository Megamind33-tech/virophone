process.env.NODE_ENV = 'test';
// Keep global rate limiting effectively disabled for the high request volume of
// the integration suite (production uses the real RATE_LIMIT_* values).
process.env.RATE_LIMIT_MAX_REQUESTS = '1000000';
process.env.JWT_ACCESS_SECRET = process.env.JWT_ACCESS_SECRET || 'test_access_secret';
process.env.JWT_REFRESH_SECRET = process.env.JWT_REFRESH_SECRET || 'test_refresh_secret';
process.env.CONTACT_HASH_SALT = process.env.CONTACT_HASH_SALT || 'test_contact_salt';
// Always use the fixed-code provider in Jest, even if a developer .env exported
// OTP_PROVIDER=console / mailtrap.
process.env.OTP_PROVIDER = 'test';
process.env.TEST_OTP_CODE = process.env.TEST_OTP_CODE || '123456';
process.env.EMAIL_TRANSPORT = 'console';
process.env.REDIS_URL = process.env.REDIS_URL || 'redis://localhost:6379';
process.env.EPHEMERAL_SIGNING_SECRET = process.env.EPHEMERAL_SIGNING_SECRET || 'test_ephemeral_signing_secret';
process.env.TURN_SECRET = process.env.TURN_SECRET || 'test_turn_secret';
process.env.LIVEKIT_API_KEY = process.env.LIVEKIT_API_KEY || 'test_livekit_key';
process.env.LIVEKIT_API_SECRET = process.env.LIVEKIT_API_SECRET || 'test_livekit_secret_at_least_32_chars';
process.env.LIVEKIT_URL = process.env.LIVEKIT_URL || 'wss://livekit.test.local';
