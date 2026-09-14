import { Injectable, Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';

/**
 * Development OTP provider — logs OTP to console.
 * NEVER use in production. Swap via OTP_PROVIDER env var.
 */
@Injectable()
export class ConsoleOtpProvider implements OtpProvider {
  private readonly logger = new Logger('ConsoleOtpProvider');

  async sendOtp(phoneE164: string, code: string): Promise<void> {
    this.logger.warn(
      `[DEV ONLY] OTP for ${phoneE164.slice(0, 4)}****: ${code}`,
    );
  }
}
