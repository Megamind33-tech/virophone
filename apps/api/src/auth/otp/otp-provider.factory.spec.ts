import { createOtpProvider } from './otp-provider.factory';
import { ConsoleOtpProvider } from './console-otp.provider';
import { TestOtpProvider } from './test-otp.provider';
import { HttpSmsOtpProvider } from './http-sms-otp.provider';
import { TwilioOtpProvider } from './twilio-otp.provider';

describe('createOtpProvider', () => {
  const ORIGINAL = process.env.OTP_PROVIDER;
  afterEach(() => {
    process.env.OTP_PROVIDER = ORIGINAL;
  });

  it('defaults to console', () => {
    delete process.env.OTP_PROVIDER;
    expect(createOtpProvider()).toBeInstanceOf(ConsoleOtpProvider);
  });

  it('selects the test provider', () => {
    process.env.OTP_PROVIDER = 'test';
    expect(createOtpProvider()).toBeInstanceOf(TestOtpProvider);
  });

  it('selects the http provider', () => {
    process.env.OTP_PROVIDER = 'http';
    expect(createOtpProvider()).toBeInstanceOf(HttpSmsOtpProvider);
  });

  it('selects the twilio provider', () => {
    process.env.OTP_PROVIDER = 'twilio';
    expect(createOtpProvider()).toBeInstanceOf(TwilioOtpProvider);
  });

  it('falls back to console for unknown values', () => {
    process.env.OTP_PROVIDER = 'nope';
    expect(createOtpProvider()).toBeInstanceOf(ConsoleOtpProvider);
  });
});
