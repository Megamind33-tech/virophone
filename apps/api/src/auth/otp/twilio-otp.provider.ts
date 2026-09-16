import { Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';

/**
 * Twilio SMS provider (Programmable Messaging API).
 *
 * Configure via env:
 *   TWILIO_ACCOUNT_SID
 *   TWILIO_AUTH_TOKEN
 *   TWILIO_FROM            - a Twilio phone number or Messaging Service SID
 *   SMS_BODY_TEMPLATE      - optional, `{code}` substituted
 *
 * Credentials are supplied by the operator; inert until then.
 */
export class TwilioOtpProvider implements OtpProvider {
  private readonly logger = new Logger('TwilioOtpProvider');
  private readonly sid = process.env.TWILIO_ACCOUNT_SID || '';
  private readonly token = process.env.TWILIO_AUTH_TOKEN || '';
  private readonly from = process.env.TWILIO_FROM || '';
  private readonly template =
    process.env.SMS_BODY_TEMPLATE || 'Your Viro Reach code is {code}';

  async sendOtp(phoneE164: string, code: string): Promise<void> {
    if (!this.sid || !this.token || !this.from) {
      throw new Error(
        'TwilioOtpProvider requires TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM.',
      );
    }
    const url = `https://api.twilio.com/2010-04-01/Accounts/${this.sid}/Messages.json`;
    const form = new URLSearchParams();
    form.set('To', phoneE164);
    // From may be a phone number (From) or a Messaging Service SID.
    if (this.from.startsWith('MG')) {
      form.set('MessagingServiceSid', this.from);
    } else {
      form.set('From', this.from);
    }
    form.set('Body', this.template.replace('{code}', code));

    const auth = Buffer.from(`${this.sid}:${this.token}`).toString('base64');
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${auth}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: form.toString(),
    });
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      throw new Error(`Twilio responded ${res.status}: ${detail.slice(0, 200)}`);
    }
    this.logger.log(`OTP SMS dispatched via Twilio to ${phoneE164.slice(0, 4)}****`);
  }
}
