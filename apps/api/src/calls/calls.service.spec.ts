import { CallsService } from './calls.service';
import { BlocksService } from '../blocks/blocks.service';

describe('CallsService - Authorization', () => {
  let service: CallsService;
  const mockCallRepo = { create: jest.fn(), save: jest.fn() };
  const mockMatchRepo = { findOne: jest.fn() };
  const mockConnectionRepo = { findOne: jest.fn() };
  const mockProfileRepo = { findOne: jest.fn() };
  const mockBlocks = { isBlocked: jest.fn().mockResolvedValue(false) } as unknown as BlocksService;

  beforeEach(() => {
    jest.clearAllMocks();
    (mockBlocks.isBlocked as jest.Mock).mockResolvedValue(false);
    mockCallRepo.create.mockImplementation((data) => ({ id: 'call-1', ...data }));
    mockCallRepo.save.mockImplementation((data) => data);
    service = new CallsService(
      mockCallRepo as any,
      mockMatchRepo as any,
      mockConnectionRepo as any,
      mockProfileRepo as any,
      mockBlocks,
    );
  });

  it('denies unknown caller relationship', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue(null);
    mockProfileRepo.findOne.mockResolvedValue({ allowCallsFromViroId: 'CONNECTIONS_ONLY' });

    await expect(service.authorize('caller', 'target')).rejects.toThrow();
  });

  it('authorizes known phone contact', async () => {
    mockMatchRepo.findOne.mockResolvedValue({ userId: 'caller', matchedUserId: 'target' });

    const result = await service.authorize('caller', 'target');
    expect(result.authorized).toBe(true);
    expect(result.callId).toBe('call-1');
  });

  it('denies blocked relationship', async () => {
    (mockBlocks.isBlocked as jest.Mock).mockResolvedValue(true);
    await expect(service.authorize('caller', 'target')).rejects.toThrow();
  });

  it('authorizes accepted connection', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue({
      requesterUserId: 'caller',
      recipientUserId: 'target',
      status: 'ACCEPTED',
    });

    const result = await service.authorize('caller', 'target');
    expect(result.authorized).toBe(true);
  });
});
