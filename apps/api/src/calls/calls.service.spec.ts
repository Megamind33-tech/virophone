import { CallsService } from './calls.service';
import { BlocksService } from '../blocks/blocks.service';
import { CallSessionService } from './call-session.service';
import { RedisService } from '../redis/redis.service';

describe('CallsService - Authorization', () => {
  let service: CallsService;
  const mockCallRepo = { create: jest.fn(), save: jest.fn() };
  const mockCallQualityRepo = { findOne: jest.fn(), create: jest.fn(), save: jest.fn() };
  const mockPush = { sendToUser: jest.fn().mockResolvedValue(undefined) } as any;
  const mockMatchRepo = { findOne: jest.fn() };
  const mockConnectionRepo = { findOne: jest.fn() };
  const mockProfileRepo = { findOne: jest.fn() };
  const mockDeviceRepo = { findOne: jest.fn(), find: jest.fn() };
  const mockBlocks = { isBlocked: jest.fn().mockResolvedValue(false) } as unknown as BlocksService;
  const mockCallSession = {
    createSession: jest.fn().mockResolvedValue({}),
  } as unknown as CallSessionService;
  const mockRedis = {
    getJson: jest.fn().mockResolvedValue({ deviceId: 'callee-device' }),
    sMembers: jest.fn().mockResolvedValue(['callee-device']),
  } as unknown as RedisService;
  const mockOfflineTrust = { verifyOfflineCallTicket: jest.fn().mockReturnValue(false) } as any;

  beforeEach(() => {
    jest.clearAllMocks();
    (mockBlocks.isBlocked as jest.Mock).mockResolvedValue(false);
    mockCallRepo.create.mockImplementation((data) => ({ id: 'call-1', ...data }));
    mockCallRepo.save.mockImplementation((data) => data);
    mockDeviceRepo.findOne.mockResolvedValue({ id: 'callee-device', userId: 'target' });
    mockDeviceRepo.find.mockResolvedValue([{ id: 'callee-device' }]);
    service = new CallsService(
      mockCallRepo as any,
      mockCallQualityRepo as any,
      mockMatchRepo as any,
      mockConnectionRepo as any,
      mockProfileRepo as any,
      mockDeviceRepo as any,
      mockBlocks,
      mockCallSession,
      mockRedis,
      mockPush,
      mockOfflineTrust,
    );
  });

  it('denies unknown caller relationship', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue(null);
    mockProfileRepo.findOne.mockResolvedValue({ allowCallsFromViroId: 'CONNECTIONS_ONLY' });

    await expect(service.authorize('caller', 'caller-device', 'target')).rejects.toThrow();
  });

  it('authorizes known phone contact', async () => {
    mockMatchRepo.findOne.mockResolvedValue({ userId: 'caller', matchedUserId: 'target' });

    const result = await service.authorize('caller', 'caller-device', 'target');
    expect(result.authorized).toBe(true);
    expect(result.callId).toBe('call-1');
    expect(result.sessionMaterial?.calleeDeviceId).toBe('callee-device');
  });

  it('denies blocked relationship', async () => {
    (mockBlocks.isBlocked as jest.Mock).mockResolvedValue(true);
    await expect(service.authorize('caller', 'caller-device', 'target')).rejects.toThrow();
  });

  it('authorizes via a valid offline call ticket when no relationship exists', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue(null);
    mockProfileRepo.findOne.mockResolvedValue({ allowCallsFromViroId: 'CONNECTIONS_ONLY' });
    (mockOfflineTrust.verifyOfflineCallTicket as jest.Mock).mockReturnValue(true);

    const result = await service.authorize('caller', 'caller-device', 'target', undefined, 'valid-ticket');
    expect(result.authorized).toBe(true);
    expect(mockOfflineTrust.verifyOfflineCallTicket).toHaveBeenCalledWith(
      'valid-ticket',
      'caller',
      'caller-device',
      'target',
    );
  });

  it('denies when the offline ticket is invalid and no relationship exists', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue(null);
    mockProfileRepo.findOne.mockResolvedValue({ allowCallsFromViroId: 'CONNECTIONS_ONLY' });
    (mockOfflineTrust.verifyOfflineCallTicket as jest.Mock).mockReturnValue(false);

    await expect(
      service.authorize('caller', 'caller-device', 'target', undefined, 'bad-ticket'),
    ).rejects.toThrow();
  });

  it('authorizes accepted connection', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue({
      requesterUserId: 'caller',
      recipientUserId: 'target',
      status: 'ACCEPTED',
    });

    const result = await service.authorize('caller', 'caller-device', 'target');
    expect(result.authorized).toBe(true);
  });

  it('rings every online callee device', async () => {
    mockMatchRepo.findOne.mockResolvedValue({ userId: 'caller', matchedUserId: 'target' });
    (mockRedis.sMembers as jest.Mock).mockResolvedValue(['dev-a', 'dev-b']);
    mockDeviceRepo.findOne.mockImplementation(({ where }: { where: { id: string } }) =>
      Promise.resolve({ id: where.id, userId: 'target' }),
    );

    const result = await service.authorize('caller', 'caller-device', 'target');
    expect(result.sessionMaterial?.calleeDeviceIds).toBe('dev-a,dev-b');
    expect(mockCallSession.createSession).toHaveBeenCalledWith(
      expect.objectContaining({
        calleeDeviceIds: ['dev-a', 'dev-b'],
        calleeDeviceId: 'dev-a',
      }),
    );
  });
});
