import { Logger } from '@nestjs/common';
import { OtpProvider } from '../otp/otp-provider.interface';

/**
 * Development email OTP provider — logs the code instead of sending.
 * Used when EMAIL_TRANSPORT is not configured. NEVER for production.
 */
export class ConsoleEmailProvider implements OtpProvider {
  private readonly logger = new Logger('ConsoleEmailProvider');

  async sendOtp(email: string, code: string): Promise<void> {
    if (process.env.NODE_ENV === 'production') {
      this.logger.log(`OTP email dispatched to ${maskEmail(email)} (code not logged)`);
      return;
    }
    this.logger.warn(`[DEV ONLY] Email OTP for ${maskEmail(email)}: ${code}`);
  }
}

export function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  if (!domain) return '***';
  const head = local.slice(0, 2);
  return `${head}***@${domain}`;
}
