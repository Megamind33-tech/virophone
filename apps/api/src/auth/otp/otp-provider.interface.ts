export interface OtpProvider {
  sendOtp(phoneE164: string, code: string): Promise<void>;
}
