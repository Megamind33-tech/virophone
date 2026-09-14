import { createHmac } from 'crypto';

/**
 * SERVER-ONLY deterministic keyed hash for phone_identities.phone_hash storage.
 * NOT private-set intersection. NOT used by clients.
 * Provides stable server-side lookup keys; salt must never leave the server.
 */
export function hashPhoneForStorage(phoneE164: string, salt: string): string {
  return createHmac('sha256', salt).update(phoneE164).digest('hex');
}

/** @deprecated Use hashPhoneForStorage — kept for migration compatibility */
export const hashPhoneForMatching = hashPhoneForStorage;

export function hashRefreshToken(token: string): string {
  return createHmac('sha256', process.env.JWT_REFRESH_SECRET || 'dev')
    .update(token)
    .digest('hex');
}
