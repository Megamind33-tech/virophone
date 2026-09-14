import { createHmac } from 'crypto';
import { hashRefreshToken } from '../common/utils/hash.util';

describe('Auth Security', () => {
  it('hashes refresh tokens consistently', () => {
    const token = 'test-refresh-token';
    const hash1 = hashRefreshToken(token);
    const hash2 = hashRefreshToken(token);
    expect(hash1).toBe(hash2);
    expect(hash1).not.toBe(token);
  });

  it('produces different hashes for different tokens', () => {
    expect(hashRefreshToken('token-a')).not.toBe(hashRefreshToken('token-b'));
  });

  it('hashes OTP codes', () => {
    const hash = createHmac('sha256', 'dev').update('123456').digest('hex');
    expect(hash).toHaveLength(64);
  });
});
