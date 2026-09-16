import { createEmailOtpProvider } from './email-otp.factory';
import { ConsoleEmailProvider } from './console-email.provider';
import { SmtpEmailProvider } from './smtp-email.provider';

describe('createEmailOtpProvider', () => {
  const ORIGINAL = process.env.EMAIL_TRANSPORT;
  afterEach(() => {
    process.env.EMAIL_TRANSPORT = ORIGINAL;
  });

  it('defaults to the console (log) email provider', () => {
    delete process.env.EMAIL_TRANSPORT;
    expect(createEmailOtpProvider()).toBeInstanceOf(ConsoleEmailProvider);
  });

  it('selects the SMTP provider when configured', () => {
    process.env.EMAIL_TRANSPORT = 'smtp';
    expect(createEmailOtpProvider()).toBeInstanceOf(SmtpEmailProvider);
  });
});
