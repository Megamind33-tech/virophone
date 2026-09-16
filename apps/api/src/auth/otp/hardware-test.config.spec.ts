import {
  getHardwareTestOtpCode,
  isPhoneHardwareTestAllowed,
  normalizeHardwareTestAllowlist,
  validateHardwareTestCredential,
  validateHardwareTestProductionConfig,
} from './hardware-test.config';

const PHONE_A = '+260961582985';
const PHONE_B = '+260977426940';

describe('Hardware-test configuration', () => {
  const baseEnv: NodeJS.ProcessEnv = {
    OTP_PROVIDER: 'hardware-test',
    HARDWARE_TEST_PHONES_E164: `${PHONE_A},${PHONE_B}`,
    HARDWARE_TEST_OTP_CODE: 'Hw7k9m',
  };

  it('accepts allowlisted Phone A and Phone B', () => {
    const list = normalizeHardwareTestAllowlist(baseEnv.HARDWARE_TEST_PHONES_E164);
    expect(list).toEqual([PHONE_A, PHONE_B]);
    expect(isPhoneHardwareTestAllowed(PHONE_A, baseEnv)).toBe(true);
    expect(isPhoneHardwareTestAllowed(PHONE_B, baseEnv)).toBe(true);
  });

  it('rejects non-allowlisted valid Zambian number', () => {
    expect(isPhoneHardwareTestAllowed('+260971100001', baseEnv)).toBe(false);
  });

  it('rejects invalid phone numbers in allowlist config', () => {
    expect(() =>
      normalizeHardwareTestAllowlist('+260000000000,not-a-phone'),
    ).toThrow(/Invalid hardware-test allowlist/);
  });

  it('rejects wildcard allowlist tokens', () => {
    expect(() => normalizeHardwareTestAllowlist('*')).toThrow(/Unsafe/);
    expect(() => normalizeHardwareTestAllowlist('ALL')).toThrow(/Unsafe/);
    expect(() => normalizeHardwareTestAllowlist('any')).toThrow(/Unsafe/);
  });

  it('rejects empty allowlist at startup validation', () => {
    expect(() =>
      validateHardwareTestProductionConfig({
        ...baseEnv,
        HARDWARE_TEST_PHONES_E164: '',
      }),
    ).toThrow(/non-empty/);
  });

  it('rejects weak credentials', () => {
    expect(() => validateHardwareTestCredential('123456')).toThrow(/too weak/);
    expect(() => validateHardwareTestCredential('000000')).toThrow(/too weak/);
  });

  it('rejects missing or short credentials', () => {
    expect(() => validateHardwareTestCredential(undefined)).toThrow(/6–32/);
    expect(() => validateHardwareTestCredential('abc')).toThrow(/6–32/);
  });

  it('returns configured credential only from env (never hardcoded)', () => {
    expect(getHardwareTestOtpCode(baseEnv)).toBe('Hw7k9m');
  });

  it('does not treat phones as allowlisted when provider is not hardware-test', () => {
    expect(
      isPhoneHardwareTestAllowed(PHONE_A, { ...baseEnv, OTP_PROVIDER: 'console' }),
    ).toBe(false);
  });
});
