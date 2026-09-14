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
    service = new ContactsService(
      mockPhoneRepo as any,
      mockProfileRepo as any,
      mockMatchRepo as any,
      mockBlockRepo as any,
      mockConnectionRepo as any,
      mockSecurity,
    );
  });

  it('returns empty for no matching phones', async () => {
    mockPhoneRepo.find.mockResolvedValue([]);
    const result = await service.discover('user-1', ['hash1', 'hash2']);
    expect(result.matches).toHaveLength(0);
  });

  it('returns only submitted contact matches', async () => {
    mockPhoneRepo.find.mockResolvedValue([
      { userId: 'user-2', phoneHash: 'hash1', status: 'VERIFIED' },
      { userId: 'user-3', phoneHash: 'hash-unknown', status: 'VERIFIED' },
    ]);
    mockProfileRepo.findOne.mockImplementation(({ where }: any) => {
      if (where.userId === 'user-2') {
        return { userId: 'user-2', displayName: 'Brian', viroId: '@brian.m', avatarUrl: null };
      }
      return null;
    });

    const result = await service.discover('user-1', ['hash1']);
    expect(result.matches).toHaveLength(1);
    expect(result.matches[0].userId).toBe('user-2');
  });

  it('excludes blocked users', async () => {
    mockPhoneRepo.find.mockResolvedValue([
      { userId: 'user-blocked', phoneHash: 'hash1', status: 'VERIFIED' },
    ]);
    mockBlockRepo.find.mockResolvedValue([
      { blockerUserId: 'user-1', blockedUserId: 'user-blocked' },
    ]);

    const result = await service.discover('user-1', ['hash1']);
    expect(result.matches).toHaveLength(0);
  });

  it('does not return self', async () => {
    mockPhoneRepo.find.mockResolvedValue([
      { userId: 'user-1', phoneHash: 'hash1', status: 'VERIFIED' },
    ]);

    const result = await service.discover('user-1', ['hash1']);
    expect(result.matches).toHaveLength(0);
  });

  it('rejects oversized batches', async () => {
    const hashes = Array.from({ length: 201 }, (_, i) => `hash${i}`);
    await expect(service.discover('user-1', hashes)).rejects.toThrow();
  });
});
