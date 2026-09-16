import { Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';

/**
 * Hardware-test OTP delivery — only used when OTP_PROVIDER=hardware-test.
 * Code is fixed via HARDWARE_TEST_OTP_CODE (stored outside Git on VPS).
 * Never logs the code unless HARDWARE_TEST_LOG_OTP=true (operator debugging only).
 */
export class HardwareTestOtpProvider implements OtpProvider {
  private readonly logger = new Logger('HardwareTestOtpProvider');

  async sendOtp(phoneE164: string, code: string): Promise<void> {
    const masked = phoneE164.slice(0, 4) + '****' + phoneE164.slice(-2);
    if (process.env.HARDWARE_TEST_LOG_OTP === 'true') {
      this.logger.warn(`[HARDWARE TEST ONLY] OTP for ${masked}: ${code}`);
    } else {
      this.logger.log(`Hardware test OTP dispatched for ${masked} (code not logged)`);
    }
  }
}
