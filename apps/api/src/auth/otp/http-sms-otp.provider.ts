import { Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';

/**
 * Generic HTTP JSON SMS gateway provider.
 *
 * Works with any SMS API that accepts a JSON POST. Configure via env:
 *   SMS_API_URL   - endpoint to POST to
 *   SMS_API_KEY   - bearer token (optional)
 *   SMS_FROM      - sender id/number (optional)
 *   SMS_BODY_TEMPLATE - message template, `{code}` is substituted
 *                       (default: "Your Viro Reach code is {code}")
 *
 * Payload: { "to": "<e164>", "from": "<from>", "text": "<message>" }
 * Credentials are supplied by the operator; this class is inert until then.
 */
export class HttpSmsOtpProvider implements OtpProvider {
  private readonly logger = new Logger('HttpSmsOtpProvider');
  private readonly url = process.env.SMS_API_URL || '';
  private readonly apiKey = process.env.SMS_API_KEY || '';
  private readonly from = process.env.SMS_FROM || 'ViroReach';
  private readonly template =
    process.env.SMS_BODY_TEMPLATE || 'Your Viro Reach code is {code}';

  async sendOtp(phoneE164: string, code: string): Promise<void> {
    if (!this.url) {
      throw new Error('HttpSmsOtpProvider requires SMS_API_URL to be set.');
    }
    const body = this.template.replace('{code}', code);
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.apiKey) headers.Authorization = `Bearer ${this.apiKey}`;

    const res = await fetch(this.url, {
      method: 'POST',
      headers,
      body: JSON.stringify({ to: phoneE164, from: this.from, text: body }),
    });
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      throw new Error(`SMS gateway responded ${res.status}: ${detail.slice(0, 200)}`);
    }
    this.logger.log(`OTP SMS dispatched to ${phoneE164.slice(0, 4)}****`);
  }
}
