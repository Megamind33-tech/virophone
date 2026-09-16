import { isUserUuid } from './call-target.util';

describe('call target UUID validation', () => {
  it('accepts valid UUID', () => {
    expect(isUserUuid('aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee')).toBe(true);
  });

  it('rejects E.164 phone', () => {
    expect(isUserUuid('+260977426940')).toBe(false);
  });
});
