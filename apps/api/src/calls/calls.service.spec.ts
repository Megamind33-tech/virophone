import { CallsService } from './calls.service';
import { BlocksService } from '../blocks/blocks.service';
import { CallSessionService } from './call-session.service';
import { RedisService } from '../redis/redis.service';

const CALLER = '11111111-1111-4111-8111-111111111111';
const TARGET = '22222222-2222-4222-8222-222222222222';
const CALLER_DEVICE = '33333333-3333-4333-8333-333333333333';
const CALLEE_DEVICE = '44444444-4444-4444-8444-444444444444';

describe('CallsService - Authorization', () => {
  let service: CallsService;
  const mockCallRepo = { create: jest.fn(), save: jest.fn() };
  const mockCallQualityRepo = { findOne: jest.fn(), create: jest.fn(), save: jest.fn() };
  const mockPush = { sendToUser: jest.fn().mockResolvedValue(undefined) } as any;
  const mockMatchRepo = { findOne: jest.fn() };
  const mockConnectionRepo = { findOne: jest.fn() };
  const mockProfileRepo = { findOne: jest.fn() };
  const mockPhoneRepo = { findOne: jest.fn() };
  const mockDeviceRepo = { findOne: jest.fn(), find: jest.fn() };
  const mockBlocks = { isBlocked: jest.fn().mockResolvedValue(false) } as unknown as BlocksService;
  const mockCallSession = {
    createSession: jest.fn().mockResolvedValue({}),
  } as unknown as CallSessionService;
  const mockRedis = {
    getJson: jest.fn().mockResolvedValue({ deviceId: CALLEE_DEVICE }),
    sMembers: jest.fn().mockResolvedValue([CALLEE_DEVICE]),
  } as unknown as RedisService;
  const mockOfflineTrust = { verifyOfflineCallTicket: jest.fn().mockReturnValue(false) } as any;

  beforeEach(() => {
    jest.clearAllMocks();
    (mockBlocks.isBlocked as jest.Mock).mockResolvedValue(false);
    mockCallRepo.create.mockImplementation((data) => ({ id: 'call-1', ...data }));
    mockCallRepo.save.mockImplementation((data) => data);
    mockDeviceRepo.findOne.mockResolvedValue({ id: CALLEE_DEVICE, userId: TARGET });
    mockDeviceRepo.find.mockResolvedValue([{ id: CALLEE_DEVICE }]);
    service = new CallsService(
      mockCallRepo as any,
      mockCallQualityRepo as any,
      mockMatchRepo as any,
      mockConnectionRepo as any,
      mockProfileRepo as any,
      mockPhoneRepo as any,
      mockDeviceRepo as any,
      mockBlocks,
      mockCallSession,
      mockRedis,
      mockPush,
      mockOfflineTrust,
    );
  });

  it('rejects phone number where UUID is expected', async () => {
    await expect(
      service.authorize(CALLER, CALLER_DEVICE, '+260977426940'),
    ).rejects.toMatchObject({
      response: { code: 'INVALID_TARGET' },
    });
  });

  it('denies unknown caller relationship', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue(null);
    mockProfileRepo.findOne.mockResolvedValue({ allowCallsFromViroId: 'CONNECTIONS_ONLY' });

    await expect(service.authorize(CALLER, CALLER_DEVICE, TARGET)).rejects.toThrow();
  });

  it('authorizes known phone contact', async () => {
    mockMatchRepo.findOne.mockResolvedValue({ userId: CALLER, matchedUserId: TARGET });

    const result = await service.authorize(CALLER, CALLER_DEVICE, TARGET);
    expect(result.authorized).toBe(true);
    expect(result.callId).toBe('call-1');
    expect(result.sessionMaterial?.calleeDeviceId).toBe(CALLEE_DEVICE);
  });

  it('denies blocked relationship', async () => {
    (mockBlocks.isBlocked as jest.Mock).mockResolvedValue(true);
    await expect(service.authorize(CALLER, CALLER_DEVICE, TARGET)).rejects.toThrow();
  });

  it('authorizes via a valid offline call ticket when no relationship exists', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue(null);
    mockProfileRepo.findOne.mockResolvedValue({ allowCallsFromViroId: 'CONNECTIONS_ONLY' });
    (mockOfflineTrust.verifyOfflineCallTicket as jest.Mock).mockReturnValue(true);

    const result = await service.authorize(CALLER, CALLER_DEVICE, TARGET, undefined, 'valid-ticket');
    expect(result.authorized).toBe(true);
    expect(mockOfflineTrust.verifyOfflineCallTicket).toHaveBeenCalledWith(
      'valid-ticket',
      CALLER,
      CALLER_DEVICE,
      TARGET,
    );
  });

  it('denies when the offline ticket is invalid and no relationship exists', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue(null);
    mockProfileRepo.findOne.mockResolvedValue({ allowCallsFromViroId: 'CONNECTIONS_ONLY' });
    (mockOfflineTrust.verifyOfflineCallTicket as jest.Mock).mockReturnValue(false);

    await expect(
      service.authorize(CALLER, CALLER_DEVICE, TARGET, undefined, 'bad-ticket'),
    ).rejects.toThrow();
  });

  it('authorizes accepted connection', async () => {
    mockMatchRepo.findOne.mockResolvedValue(null);
    mockConnectionRepo.findOne.mockResolvedValue({
      requesterUserId: CALLER,
      recipientUserId: TARGET,
      status: 'ACCEPTED',
    });

    const result = await service.authorize(CALLER, CALLER_DEVICE, TARGET);
    expect(result.authorized).toBe(true);
  });

  it('rings every online callee device', async () => {
    mockMatchRepo.findOne.mockResolvedValue({ userId: CALLER, matchedUserId: TARGET });
    (mockRedis.sMembers as jest.Mock).mockResolvedValue(['dev-a', 'dev-b']);
    mockDeviceRepo.findOne.mockImplementation(({ where }: { where: { id: string } }) =>
      Promise.resolve({ id: where.id, userId: TARGET }),
    );

    const result = await service.authorize(CALLER, CALLER_DEVICE, TARGET);
    expect(result.sessionMaterial?.calleeDeviceIds).toBe('dev-a,dev-b');
    expect(mockCallSession.createSession).toHaveBeenCalledWith(
      expect.objectContaining({
        calleeDeviceIds: ['dev-a', 'dev-b'],
        calleeDeviceId: 'dev-a',
      }),
    );
  });
});
