import { createHmac } from 'crypto';

/**
 * Keyed hash for contact phone matching.
 * Server never stores raw phone numbers from contact uploads in logs.
 */
export function hashPhoneForMatching(phoneE164: string, salt: string): string {
  return createHmac('sha256', salt).update(phoneE164).digest('hex');
}

export function hashRefreshToken(token: string): string {
  return createHmac('sha256', process.env.JWT_REFRESH_SECRET || 'dev')
    .update(token)
    .digest('hex');
}
