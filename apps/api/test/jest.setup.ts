process.env.NODE_ENV = 'test';
process.env.JWT_ACCESS_SECRET = process.env.JWT_ACCESS_SECRET || 'test_access_secret';
process.env.JWT_REFRESH_SECRET = process.env.JWT_REFRESH_SECRET || 'test_refresh_secret';
process.env.CONTACT_HASH_SALT = process.env.CONTACT_HASH_SALT || 'test_contact_salt';
process.env.OTP_PROVIDER = process.env.OTP_PROVIDER || 'test';
process.env.TEST_OTP_CODE = process.env.TEST_OTP_CODE || '123456';
