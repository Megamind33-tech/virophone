import { Logger } from '@nestjs/common';
import { OtpProvider } from '../otp/otp-provider.interface';
import { maskEmail } from './console-email.provider';

/**
 * Mailtrap email OTP provider (HTTP Sending API).
 *
 * Env:
 *   MAILTRAP_TOKEN        - API token (required)
 *   MAILTRAP_MODE         - "sandbox" (default) or "live"
 *   MAILTRAP_INBOX_ID     - required for sandbox mode
 *   EMAIL_FROM            - "Name <email>" or "email" (default Viro Reach sender)
 *   EMAIL_SUBJECT         - optional
 *   EMAIL_BODY_TEMPLATE   - optional, `{code}` substituted
 *
 * Sandbox delivers to the Mailtrap inbox (safe for development). Live requires a
 * verified sending domain; switch with MAILTRAP_MODE=live once DNS is verified.
 */
export class MailtrapEmailProvider implements OtpProvider {
  private readonly logger = new Logger('MailtrapEmailProvider');
  private readonly token = process.env.MAILTRAP_TOKEN || '';
  private readonly mode = (process.env.MAILTRAP_MODE || 'sandbox').toLowerCase();
  private readonly inboxId = process.env.MAILTRAP_INBOX_ID || '';

  private endpoint(): string {
    if (this.mode === 'live') {
      return process.env.MAILTRAP_LIVE_URL || 'https://send.api.mailtrap.io/api/send';
    }
    if (!this.inboxId) {
      throw new Error('MailtrapEmailProvider sandbox mode requires MAILTRAP_INBOX_ID.');
    }
    return `https://sandbox.api.mailtrap.io/api/send/${this.inboxId}`;
  }

  private parseFrom(): { email: string; name: string } {
    const raw = process.env.EMAIL_FROM || 'Viro Reach <no-reply@viro-reach.local>';
    const match = raw.match(/^\s*(.*?)\s*<([^>]+)>\s*$/);
    if (match) return { name: match[1] || 'Viro Reach', email: match[2] };
    return { name: 'Viro Reach', email: raw.trim() };
  }

  async sendOtp(email: string, code: string): Promise<void> {
    if (!this.token) {
      throw new Error('MailtrapEmailProvider requires MAILTRAP_TOKEN.');
    }
    const subject = process.env.EMAIL_SUBJECT || 'Your Viro Reach code';
    const template =
      process.env.EMAIL_BODY_TEMPLATE ||
      'Your Viro Reach verification code is {code}. It expires in 5 minutes.';
    const body = {
      from: this.parseFrom(),
      to: [{ email }],
      subject,
      text: template.replace('{code}', code),
      category: 'otp',
    };

    const res = await fetch(this.endpoint(), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${this.token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      throw new Error(`Mailtrap responded ${res.status}: ${detail.slice(0, 300)}`);
    }
    this.logger.log(`OTP email dispatched via Mailtrap (${this.mode}) to ${maskEmail(email)}`);
  }
}
