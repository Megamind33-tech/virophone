import { Logger } from '@nestjs/common';
import * as nodemailer from 'nodemailer';
import { OtpProvider } from '../otp/otp-provider.interface';
import { maskEmail } from './console-email.provider';

/**
 * SMTP email OTP provider (works with any affordable SMTP mailbox / relay).
 *
 * Configure via env:
 *   SMTP_HOST, SMTP_PORT (default 587), SMTP_SECURE ("true" for 465)
 *   SMTP_USER, SMTP_PASS
 *   EMAIL_FROM             - e.g. "Viro Reach <no-reply@yourdomain.com>"
 *   EMAIL_SUBJECT          - optional, default "Your Viro Reach code"
 *   EMAIL_BODY_TEMPLATE    - optional, `{code}` substituted
 *
 * Credentials are supplied by the operator; inert until then.
 */
export class SmtpEmailProvider implements OtpProvider {
  private readonly logger = new Logger('SmtpEmailProvider');
  private transporter: nodemailer.Transporter | null = null;

  private getTransporter(): nodemailer.Transporter {
    if (this.transporter) return this.transporter;
    const host = process.env.SMTP_HOST;
    if (!host) {
      throw new Error('SmtpEmailProvider requires SMTP_HOST.');
    }
    this.transporter = nodemailer.createTransport({
      host,
      port: parseInt(process.env.SMTP_PORT || '587', 10),
      secure: process.env.SMTP_SECURE === 'true',
      auth:
        process.env.SMTP_USER && process.env.SMTP_PASS
          ? { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS }
          : undefined,
    });
    return this.transporter;
  }

  async sendOtp(email: string, code: string): Promise<void> {
    const from = process.env.EMAIL_FROM || 'Viro Reach <no-reply@viro-reach.local>';
    const subject = process.env.EMAIL_SUBJECT || 'Your Viro Reach code';
    const template =
      process.env.EMAIL_BODY_TEMPLATE ||
      'Your Viro Reach verification code is {code}. It expires in 5 minutes.';
    const text = template.replace('{code}', code);

    await this.getTransporter().sendMail({ from, to: email, subject, text });
    this.logger.log(`OTP email dispatched to ${maskEmail(email)}`);
  }
}
