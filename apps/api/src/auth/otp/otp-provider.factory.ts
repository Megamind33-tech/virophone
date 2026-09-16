import { Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';
import { ConsoleOtpProvider } from './console-otp.provider';
import { TestOtpProvider } from './test-otp.provider';
import { HttpSmsOtpProvider } from './http-sms-otp.provider';
import { TwilioOtpProvider } from './twilio-otp.provider';
import { HardwareTestOtpProvider } from './hardware-test-otp.provider';

/**
 * Selects the OTP delivery provider from the OTP_PROVIDER env var:
 *   test           - fixed-code provider for automated tests
 *   hardware-test  - fixed code for allowlisted field-test phones
 *   twilio         - Twilio Programmable Messaging
 *   http           - generic HTTP JSON SMS gateway
 *   console        - (default) logs the code; development only
 */
export function createOtpProvider(): OtpProvider {
  const kind = (process.env.OTP_PROVIDER || 'console').toLowerCase();
  const logger = new Logger('OtpProviderFactory');
  switch (kind) {
    case 'test':
      return new TestOtpProvider();
    case 'hardware-test':
      logger.log('Using hardware-test OTP provider');
      return new HardwareTestOtpProvider();
    case 'twilio':
      logger.log('Using Twilio OTP provider');
      return new TwilioOtpProvider();
    case 'http':
      logger.log('Using HTTP SMS OTP provider');
      return new HttpSmsOtpProvider();
    case 'console':
      return new ConsoleOtpProvider();
    default:
      logger.warn(`Unknown OTP_PROVIDER "${kind}", falling back to console.`);
      return new ConsoleOtpProvider();
  }
}
