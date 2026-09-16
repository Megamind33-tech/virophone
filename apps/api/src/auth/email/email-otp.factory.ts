import { Logger } from '@nestjs/common';
import { OtpProvider } from '../otp/otp-provider.interface';
import { ConsoleEmailProvider } from './console-email.provider';
import { SmtpEmailProvider } from './smtp-email.provider';
import { MailtrapEmailProvider } from './mailtrap-email.provider';

/**
 * Selects the email OTP transport from EMAIL_TRANSPORT:
 *   mailtrap - Mailtrap HTTP Sending API (sandbox or live)
 *   smtp     - generic SMTP delivery (requires SMTP_* env)
 *   console  - (default) logs the code; development only
 *
 * Set EMAIL_TRANSPORT plus the provider env to send real emails — no code change.
 */
export function createEmailOtpProvider(): OtpProvider {
  const kind = (process.env.EMAIL_TRANSPORT || 'console').toLowerCase();
  const logger = new Logger('EmailOtpProviderFactory');
  if (kind === 'mailtrap') {
    logger.log('Using Mailtrap email OTP provider');
    return new MailtrapEmailProvider();
  }
  if (kind === 'smtp') {
    logger.log('Using SMTP email OTP provider');
    return new SmtpEmailProvider();
  }
  return new ConsoleEmailProvider();
}
