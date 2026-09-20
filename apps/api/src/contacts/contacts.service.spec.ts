import { ContactsService } from './contacts.service';
import { SecurityService } from '../security/security.service';

describe('ContactsService - Discovery Privacy', () => {
  let service: ContactsService;
  const mockPhoneRepo = { find: jest.fn() };
  const mockProfileRepo = { findOne: jest.fn() };
  const mockMatchRepo = { upsert: jest.fn() };
  const mockBlockRepo = { find: jest.fn().mockResolvedValue([]) };
  const mockConnectionRepo = { find: jest.fn().mockResolvedValue([]) };
  const mockSecurity = { logEvent: jest.fn() } as unknown as SecurityService;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.CONTACT_HASH_SALT = 'test_salt';
    service = new ContactsService(
      mockPhoneRepo as any,
      mockProfileRepo as any,
      mockMatchRepo as any,
      mockBlockRepo as any,
      mockConnectionRepo as any,
      mockSecurity,
      // Photo visibility: this suite is about matching, so everything is visible.
      { canSee: jest.fn().mockResolvedValue(true) } as any,
    );
  });

  it('returns empty for no matching phones', async () => {
    mockPhoneRepo.find.mockResolvedValue([]);
    const result = await service.discover('user-1', ['+260961582985']);
    expect(result.matches).toHaveLength(0);
  });

  it('returns only submitted contact matches', async () => {
    const { hashPhoneForStorage } = require('../common/utils/hash.util');
    const hash = hashPhoneForStorage('+260961582985', 'test_salt');
    mockPhoneRepo.find.mockResolvedValue([
      { userId: 'user-2', phoneHash: hash, status: 'VERIFIED' },
    ]);
    mockProfileRepo.findOne.mockImplementation(({ where }: any) => {
      if (where.userId === 'user-2') {
        return { userId: 'user-2', displayName: 'Brian', viroId: '@brian.m', avatarUrl: null };
      }
      return null;
    });

    const result = await service.discover('user-1', ['+260961582985']);
    expect(result.matches).toHaveLength(1);
    expect(result.matches[0].userId).toBe('user-2');
    expect(result.matches[0].phoneE164).toBe('+260961582985');
  });

  it('excludes blocked users', async () => {
    const { hashPhoneForStorage } = require('../common/utils/hash.util');
    const hash = hashPhoneForStorage('+260961582985', 'test_salt');
    mockPhoneRepo.find.mockResolvedValue([
      { userId: 'user-blocked', phoneHash: hash, status: 'VERIFIED' },
    ]);
    mockBlockRepo.find.mockResolvedValue([
      { blockerUserId: 'user-1', blockedUserId: 'user-blocked' },
    ]);

    const result = await service.discover('user-1', ['+260961582985']);
    expect(result.matches).toHaveLength(0);
  });

  it('does not return self', async () => {
    const { hashPhoneForStorage } = require('../common/utils/hash.util');
    const hash = hashPhoneForStorage('+260961582985', 'test_salt');
    mockPhoneRepo.find.mockResolvedValue([
      { userId: 'user-1', phoneHash: hash, status: 'VERIFIED' },
    ]);

    const result = await service.discover('user-1', ['+260961582985']);
    expect(result.matches).toHaveLength(0);
  });

  it('skips unparseable phone entries instead of rejecting the whole batch', async () => {
    // A device contact book routinely has USSD codes / garbage entries
    // (e.g. "*114#") alongside real numbers — one bad entry must not sink
    // discovery for every other, valid number sent in the same request.
    mockPhoneRepo.find.mockResolvedValue([]);
    const result = await service.discover('user-1', ['not-a-phone', '*114#']);
    expect(result.matches).toHaveLength(0);
  });

  it('rejects oversized batches', async () => {
    const phones = Array.from({ length: 201 }, (_, i) => `+26096158${String(i).padStart(4, '0')}`);
    await expect(service.discover('user-1', phones)).rejects.toThrow();
  });
});
