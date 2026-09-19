import { Logger } from '@nestjs/common';
import { OtpProvider } from './otp-provider.interface';

/**
 * Beem Africa SMS provider.
 *
 * Chosen for Zambian and wider African reach, where Twilio delivery is patchy
 * and expensive. Configure via env:
 *   BEEM_API_KEY      - API key from the Beem dashboard
 *   BEEM_SECRET_KEY   - secret key that pairs with it
 *   BEEM_SOURCE_ADDR  - approved sender id (default: INFO)
 *   BEEM_API_URL      - override the endpoint if Beem changes it
 *   SMS_BODY_TEMPLATE - message text, `{code}` is substituted
 *
 * Two details of Beem's API that are easy to get wrong and fail quietly:
 *
 *  1. `dest_addr` must NOT carry a leading `+`. Beem expects bare international
 *     digits (260961582985), and a `+` is accepted by the HTTP layer but the
 *     message never arrives — so the call looks successful and the user waits
 *     for a code that was never sent.
 *
 *  2. Beem answers 200 OK for requests it did not accept, with the real outcome
 *     in the body's `code`/`successful` fields. Trusting the HTTP status alone
 *     reports every failure as a success.
 */
export class BeemOtpProvider implements OtpProvider {
  private readonly logger = new Logger('BeemOtpProvider');
  private readonly apiKey = process.env.BEEM_API_KEY || '';
  private readonly secretKey = process.env.BEEM_SECRET_KEY || '';
  private readonly sourceAddr = process.env.BEEM_SOURCE_ADDR || 'INFO';
  private readonly url =
    process.env.BEEM_API_URL || 'https://apisms.beem.africa/v1/send';
  private readonly template =
    process.env.SMS_BODY_TEMPLATE || 'Your Viro code is {code}';

  async sendOtp(phoneE164: string, code: string): Promise<void> {
    if (!this.apiKey || !this.secretKey) {
      throw new Error(
        'BeemOtpProvider requires BEEM_API_KEY and BEEM_SECRET_KEY.',
      );
    }

    const destAddr = phoneE164.replace(/^\+/, '').replace(/\D/g, '');
    if (!destAddr) {
      throw new Error(`Cannot send OTP: unusable phone number "${phoneE164}".`);
    }

    const auth = Buffer.from(`${this.apiKey}:${this.secretKey}`).toString('base64');
    const payload = {
      source_addr: this.sourceAddr,
      schedule_time: '',
      encoding: 0,
      message: this.template.replace('{code}', code),
      recipients: [{ recipient_id: 1, dest_addr: destAddr }],
    };

    let res: Response;
    try {
      res = await fetch(this.url, {
        method: 'POST',
        headers: {
          Authorization: `Basic ${auth}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      });
    } catch (e) {
      // A network failure must surface as a failed send, not a silent one: the
      // caller decides whether to tell the user the code could not be sent.
      throw new Error(`Beem request failed: ${(e as Error).message}`);
    }

    const text = await res.text().catch(() => '');
    if (!res.ok) {
      throw new Error(`Beem responded ${res.status}: ${text.slice(0, 200)}`);
    }

    // See note 2 above: inspect the body, not just the status.
    let parsed: { successful?: boolean; code?: number; message?: string } = {};
    try {
      parsed = text ? (JSON.parse(text) as typeof parsed) : {};
    } catch {
      throw new Error(`Beem returned a non-JSON body: ${text.slice(0, 200)}`);
    }
    // Beem uses code 100 for an accepted request. `successful` is present on
    // some responses and absent on others, so neither field alone is enough.
    const accepted = parsed.successful === true || parsed.code === 100;
    if (!accepted) {
      throw new Error(
        `Beem rejected the message (code=${parsed.code ?? 'none'}): ` +
          `${parsed.message ?? text.slice(0, 200)}`,
      );
    }

    // The number is masked: an OTP log line that carries the full number turns
    // the application log into a list of everyone who has ever signed up.
    this.logger.log(`OTP SMS accepted by Beem for ${maskNumber(destAddr)}`);
  }
}

function maskNumber(digits: string): string {
  if (digits.length <= 6) return '****';
  return `${digits.slice(0, 4)}****${digits.slice(-2)}`;
}
