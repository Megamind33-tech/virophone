import { AuthService } from './auth.service';

/**
 * Phone linking deletes an account in one of its branches, so each outcome is
 * pinned here. The refusal case matters most: it is the guard standing between
 * a convenience feature and silently destroying somebody's call history.
 */
describe('AuthService phone linking', () => {
  const EMAIL_USER = 'email-user-1';
  const PHONE_USER = 'phone-user-2';
  const PHONE = '+260961582985';
  const CHALLENGE_ID = 'challenge-1';
  const CODE = '123456';

  let service: AuthService;
  let otpRepo: any;
  let phoneRepo: any;
  let emailRepo: any;
  let userRepo: any;
  let deviceRepo: any;
  let sessionRepo: any;
  let counts: { calls: number; matches: number };

  beforeEach(() => {
    process.env.OTP_PROVIDER = 'console';
    process.env.JWT_ACCESS_SECRET = 'x'.repeat(32);
    counts = { calls: 0, matches: 0 };

    otpRepo = {
      findOne: jest.fn(async () => ({
        id: CHALLENGE_ID,
        phoneE164: PHONE,
        // The service compares against its own hash, so mirror that below.
        codeHash: null as unknown as string,
        expiresAt: new Date(Date.now() + 60_000),
        attempts: 0,
        verifiedAt: null,
      })),
      save: jest.fn(async (x: unknown) => x),
      create: jest.fn((x: unknown) => x),
    };
    phoneRepo = {
      findOne: jest.fn(async () => null),
      save: jest.fn(async (x: unknown) => x),
      create: jest.fn((x: unknown) => x),
    };
    emailRepo = {
      findOne: jest.fn(async () => null),
      save: jest.fn(async (x: unknown) => x),
      delete: jest.fn(async () => undefined),
      create: jest.fn((x: unknown) => x),
    };
    // count() is reached through the repository's shared EntityManager.
    const manager = {
      count: jest.fn(async (entity: { name: string }) =>
        entity.name === 'Call' ? counts.calls : counts.matches,
      ),
    };
    userRepo = {
      findOne: jest.fn(async () => null),
      save: jest.fn(async (x: unknown) => x),
      create: jest.fn((x: unknown) => x),
      delete: jest.fn(async () => undefined),
      manager,
    };
    deviceRepo = {
      create: jest.fn((x: unknown) => ({ id: 'device-1', ...(x as object) })),
      save: jest.fn(async (x: unknown) => x),
      findOne: jest.fn(async () => null),
    };
    sessionRepo = {
      create: jest.fn((x: unknown) => x),
      save: jest.fn(async (x: unknown) => x),
      update: jest.fn(async () => undefined),
      findOne: jest.fn(async () => null),
    };

    service = new AuthService(
      userRepo,
      phoneRepo,
      { create: jest.fn(), save: jest.fn() } as any,
      deviceRepo,
      sessionRepo,
      otpRepo,
      emailRepo,
      { sign: jest.fn(() => 'signed.jwt.token') } as any,
      { logEvent: jest.fn() } as any,
      { resolveUserFromIdToken: jest.fn() } as any,
    );

    // Make the stored hash match what the service computes for CODE.
    const hashed = (service as unknown as { hashOtp(c: string): string }).hashOtp(CODE);
    otpRepo.findOne.mockImplementation(async () => ({
      id: CHALLENGE_ID,
      phoneE164: PHONE,
      codeHash: hashed,
      expiresAt: new Date(Date.now() + 60_000),
      attempts: 0,
      verifiedAt: null,
    }));
  });

  it('attaches an unclaimed number to the signed-in account', async () => {
    phoneRepo.findOne.mockResolvedValue(null);

    const result = await service.verifyPhoneLink(EMAIL_USER, CHALLENGE_ID, CODE);

    expect(result.outcome).toBe('linked');
    expect(result.userId).toBe(EMAIL_USER);
    expect(phoneRepo.save).toHaveBeenCalled();
    // Nothing was deleted: linking a free number must never remove an account.
    expect(userRepo.delete).not.toHaveBeenCalled();
  });

  it('refuses when the number belongs to another account and this one has history', async () => {
    phoneRepo.findOne.mockResolvedValue({ userId: PHONE_USER, phoneE164: PHONE });
    counts.calls = 3;

    await expect(
      service.verifyPhoneLink(EMAIL_USER, CHALLENGE_ID, CODE),
    ).rejects.toMatchObject({ response: { code: 'VALIDATION_ERROR' } });

    // The critical assertion: nothing is destroyed on the refusal path.
    expect(userRepo.delete).not.toHaveBeenCalled();
    expect(emailRepo.save).not.toHaveBeenCalled();
  });

  it('adopts the phone account when this one is empty, moving the email identity', async () => {
    phoneRepo.findOne.mockResolvedValue({ userId: PHONE_USER, phoneE164: PHONE });
    emailRepo.findOne.mockImplementation(async (q: { where: { userId: string } }) =>
      q.where.userId === EMAIL_USER
        ? { id: 'email-1', userId: EMAIL_USER, email: 'a@b.com' }
        : null,
    );

    const result = await service.verifyPhoneLink(EMAIL_USER, CHALLENGE_ID, CODE);

    expect(result.outcome).toBe('adopted');
    expect(result.userId).toBe(PHONE_USER);
    // The email sign-in now reaches the phone account.
    expect(emailRepo.save).toHaveBeenCalledWith(
      expect.objectContaining({ userId: PHONE_USER }),
    );
    expect(userRepo.delete).toHaveBeenCalledWith({ id: EMAIL_USER });
    // A session for the account they are now signed in as.
    expect(result.accessToken).toBeDefined();
  });

  it('rejects a wrong code without touching either account', async () => {
    phoneRepo.findOne.mockResolvedValue(null);

    await expect(
      service.verifyPhoneLink(EMAIL_USER, CHALLENGE_ID, '999999'),
    ).rejects.toMatchObject({ response: { code: 'VALIDATION_ERROR' } });

    expect(phoneRepo.save).not.toHaveBeenCalled();
    expect(userRepo.delete).not.toHaveBeenCalled();
  });
});
