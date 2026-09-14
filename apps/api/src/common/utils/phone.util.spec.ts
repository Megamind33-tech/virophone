import { normalizeE164, isValidE164 } from './phone.util';

describe('PhoneUtil', () => {
  it('normalizes valid E.164 numbers', () => {
    expect(normalizeE164('+260961582985')).toBe('+260961582985');
    expect(normalizeE164('+1 (555) 123-4567')).toBe('+15551234567');
  });

  it('rejects invalid numbers', () => {
    expect(normalizeE164('0961582985')).toBeNull();
    expect(normalizeE164('+123')).toBeNull();
    expect(normalizeE164('')).toBeNull();
  });

  it('validates E.164', () => {
    expect(isValidE164('+260961582985')).toBe(true);
    expect(isValidE164('invalid')).toBe(false);
  });
});
