import { normalizeE164, isValidE164 } from './phone.util';

describe('PhoneUtil (libphonenumber-js)', () => {
  it('normalizes Zambia local format 0961582985', () => {
    expect(normalizeE164('0961582985', 'ZM')).toBe('+260961582985');
  });

  it('normalizes without leading zero 961582985', () => {
    expect(normalizeE164('961582985', 'ZM')).toBe('+260961582985');
  });

  it('normalizes country code without plus 260961582985', () => {
    expect(normalizeE164('260961582985', 'ZM')).toBe('+260961582985');
  });

  it('preserves E.164 +260961582985', () => {
    expect(normalizeE164('+260961582985')).toBe('+260961582985');
  });

  it('normalizes Phone B hardware test identity +260977426940', () => {
    expect(normalizeE164('+260977426940', 'ZM')).toBe('+260977426940');
  });

  it('does not blindly prepend +260 to invalid short numbers', () => {
    expect(normalizeE164('123', 'ZM')).toBeNull();
  });

  it('rejects empty input', () => {
    expect(normalizeE164('')).toBeNull();
    expect(isValidE164('invalid')).toBe(false);
  });
});
