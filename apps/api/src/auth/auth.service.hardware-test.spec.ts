import { Test } from '@nestjs/testing';
import { getRepositoryToken } from '@nestjs/typeorm';
import { JwtService } from '@nestjs/jwt';
import { AuthService } from './auth.service';
import { User } from '../database/entities/user.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Profile } from '../database/entities/profile.entity';
import { Device } from '../database/entities/device.entity';
import { Session } from '../database/entities/session.entity';
import { OtpChallenge } from '../database/entities/otp-challenge.entity';
import { SecurityService } from '../security/security.service';
import { createHmac } from 'crypto';

describe('AuthService hardware-test flow', () => {
  const PHONE_A = '+260961582985';
  const CREDENTIAL = 'Hw7k9m';
  let authService: AuthService;
  let otpRepo: { findOne: jest.Mock; save: jest.Mock; create: jest.Mock };
  let securityService: { logEvent: jest.Mock };

  beforeEach(async () => {
    process.env.OTP_PROVIDER = 'hardware-test';
    process.env.HARDWARE_TEST_PHONES_E164 = `${PHONE_A},+260977426940`;
    process.env.HARDWARE_TEST_OTP_CODE = CREDENTIAL;
    process.env.JWT_ACCESS_SECRET = 'test_secret';

    otpRepo = {
      findOne: jest.fn(),
      save: jest.fn(async (x) => x),
      create: jest.fn((x) => ({ id: 'challenge-1', attempts: 0, ...x })),
    };
    securityService = { logEvent: jest.fn() };

    const moduleRef = await Test.createTestingModule({
      providers: [
        AuthService,
        { provide: getRepositoryToken(User), useValue: { create: jest.fn(), save: jest.fn() } },
        {
          provide: getRepositoryToken(PhoneIdentity),
          useValue: { findOne: jest.fn(), create: jest.fn(), save: jest.fn() },
        },
        { provide: getRepositoryToken(Profile), useValue: { create: jest.fn(), save: jest.fn() } },
        { provide: getRepositoryToken(Device), useValue: { create: jest.fn(), save: jest.fn() } },
        { provide: getRepositoryToken(Session), useValue: { create: jest.fn(), save: jest.fn() } },
        { provide: getRepositoryToken(OtpChallenge), useValue: otpRepo },
        { provide: JwtService, useValue: { sign: jest.fn(() => 'access-token') } },
        { provide: SecurityService, useValue: securityService },
      ],
    }).compile();

    authService = moduleRef.get(AuthService);
  });

  afterEach(() => {
    delete process.env.OTP_PROVIDER;
    delete process.env.HARDWARE_TEST_PHONES_E164;
    delete process.env.HARDWARE_TEST_OTP_CODE;
  });

  function hashOtp(code: string): string {
    return createHmac('sha256', process.env.JWT_ACCESS_SECRET || 'dev')
      .update(code)
      .digest('hex');
  }

  it('rejects non-allowlisted phone on request OTP', async () => {
    await expect(authService.requestOtp('+260971100001')).rejects.toMatchObject({
      code: 'FORBIDDEN',
    });
    expect(securityService.logEvent).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'HARDWARE_TEST_AUTH_FAILURE' }),
    );
  });

  it('creates challenge for allowlisted phone without returning credential', async () => {
    const result = await authService.requestOtp(PHONE_A);
    expect(result.challengeId).toBeDefined();
    expect(result.expiresAt).toBeDefined();
    expect(JSON.stringify(result)).not.toContain(CREDENTIAL);
  });

  it('rejects wrong credential on verify', async () => {
    otpRepo.findOne.mockResolvedValue({
      id: 'challenge-1',
      phoneE164: PHONE_A,
      codeHash: hashOtp(CREDENTIAL),
      expiresAt: new Date(Date.now() + 60_000),
      attempts: 0,
    });
    await expect(
      authService.verifyOtp('challenge-1', 'wrong1', 'pubkey', 'ANDROID', '0.1.0'),
    ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
    expect(securityService.logEvent).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'HARDWARE_TEST_AUTH_FAILURE' }),
    );
  });

  it('rejects expired challenge', async () => {
    otpRepo.findOne.mockResolvedValue({
      id: 'challenge-1',
      phoneE164: PHONE_A,
      codeHash: hashOtp(CREDENTIAL),
      expiresAt: new Date(Date.now() - 1_000),
      attempts: 0,
    });
    await expect(
      authService.verifyOtp('challenge-1', CREDENTIAL, 'pubkey', 'ANDROID', '0.1.0'),
    ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
  });
});
