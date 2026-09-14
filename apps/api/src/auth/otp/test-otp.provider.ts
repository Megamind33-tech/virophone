import { Injectable, Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';

/**
 * Test-only OTP provider — fixed code when TEST_OTP_CODE is set.
 * NEVER enabled in production.
 */
@Injectable()
export class TestOtpProvider implements OtpProvider {
  private readonly logger = new Logger('TestOtpProvider');

  async sendOtp(phoneE164: string, code: string): Promise<void> {
    const fixed = process.env.TEST_OTP_CODE;
    if (fixed && code !== fixed) {
      this.logger.warn('TestOtpProvider: code mismatch in test mode');
    }
    this.logger.debug(`[TEST] OTP for ${phoneE164}: ${process.env.TEST_OTP_CODE || code}`);
  }
}
