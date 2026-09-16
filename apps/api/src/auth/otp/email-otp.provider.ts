import { Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';
import { ViroException } from '../../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import { sendSmtpMail } from './smtp.util';

/**
 * Delivers OTP via SMTP. Used when SMS is unavailable (Phase 1A hardware testing).
 * Configure SMTP_* and OTP_DELIVERY_EMAIL (or OTP_EMAIL_MAP JSON) in .env.vps.
 */
export class EmailOtpProvider implements OtpProvider {
  private readonly logger = new Logger('EmailOtpProvider');

  async sendOtp(phoneE164: string, code: string): Promise<void> {
    const to = this.resolveRecipient(phoneE164);
    const host = process.env.SMTP_HOST;
    const port = parseInt(process.env.SMTP_PORT || '587', 10);
    const user = process.env.SMTP_USER;
    const pass = process.env.SMTP_PASS;
    const from = process.env.OTP_EMAIL_FROM || user;

    if (!host || !from || !to) {
      throw new ViroException(
        'INTERNAL_ERROR',
        'Email OTP is not configured (SMTP_HOST, OTP_EMAIL_FROM, OTP_DELIVERY_EMAIL).',
        HttpStatus.INTERNAL_SERVER_ERROR,
      );
    }

    const maskedPhone = phoneE164.slice(0, 4) + '****' + phoneE164.slice(-2);
    try {
      await sendSmtpMail({
        host,
        port,
        user: user || undefined,
        pass: pass || undefined,
        from,
        to,
        subject: 'Viro Reach verification code',
        text: `Your Viro Reach verification code is ${code}.\n\nPhone: ${maskedPhone}\nExpires in 5 minutes.\n\nIf you did not request this, ignore this message.`,
      });
      this.logger.log(`OTP email sent to ${to} for ${maskedPhone}`);
    } catch (err) {
      this.logger.error(`OTP email failed for ${maskedPhone}: ${(err as Error).message}`);
      throw new ViroException(
        'INTERNAL_ERROR',
        'Failed to send OTP email. Check SMTP configuration.',
        HttpStatus.INTERNAL_SERVER_ERROR,
      );
    }
  }

  private resolveRecipient(phoneE164: string): string | null {
    const mapJson = process.env.OTP_EMAIL_MAP;
    if (mapJson) {
      try {
        const map = JSON.parse(mapJson) as Record<string, string>;
        const mapped = map[phoneE164];
        if (mapped) return mapped;
      } catch {
        this.logger.warn('OTP_EMAIL_MAP is invalid JSON');
      }
    }
    return process.env.OTP_DELIVERY_EMAIL || null;
  }
}
