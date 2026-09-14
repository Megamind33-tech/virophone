import { OfflineTrustService, OFFLINE_TRUST_PROTOCOL_VERSION } from './offline-trust.service';

describe('OfflineTrustService - security', () => {
  const mockMatchRepo = { find: jest.fn().mockResolvedValue([]) };
  const mockConnectionRepo = { find: jest.fn().mockResolvedValue([]) };
  const mockBlocks = { isBlocked: jest.fn().mockResolvedValue(false) };

  let service: OfflineTrustService;

  beforeEach(() => {
    process.env.EPHEMERAL_SIGNING_SECRET = 'test_offline_signing_secret_32chars!!';
    service = new OfflineTrustService(
      mockMatchRepo as any,
      mockConnectionRepo as any,
      mockBlocks as any,
    );
  });

  it('mints device-bound trust tokens', async () => {
    const a = await service.getTrustMaterial('user-a', 'device-a1');
    const b = await service.getTrustMaterial('user-a', 'device-a2');
    expect(a).toHaveLength(0);
    expect(b).toHaveLength(0);
    // With peers mocked empty, verify structure via direct ticket test below
  });

  it('valid call ticket verifies for bound device', async () => {
    mockConnectionRepo.find.mockResolvedValueOnce([
      { requesterUserId: 'alice', recipientUserId: 'bob', status: 'ACCEPTED' },
    ]);
    const tickets = await service.issueOfflineCallTickets('alice', 'device-alice');
    expect(tickets).toHaveLength(1);
    const ok = service.verifyOfflineCallTicket(
      tickets[0].ticket,
      'alice',
      'device-alice',
      'bob',
    );
    expect(ok).toBe(true);
  });

  it('rejects ticket used from wrong device', async () => {
    mockConnectionRepo.find.mockResolvedValueOnce([
      { requesterUserId: 'alice', recipientUserId: 'bob', status: 'ACCEPTED' },
    ]);
    const tickets = await service.issueOfflineCallTickets('alice', 'device-alice');
    const bad = service.verifyOfflineCallTicket(
      tickets[0].ticket,
      'alice',
      'device-charlie',
      'bob',
    );
    expect(bad).toBe(false);
  });

  it('rejects ticket for wrong peer', async () => {
    mockConnectionRepo.find.mockResolvedValueOnce([
      { requesterUserId: 'alice', recipientUserId: 'bob', status: 'ACCEPTED' },
    ]);
    const tickets = await service.issueOfflineCallTickets('alice', 'device-alice');
    const bad = service.verifyOfflineCallTicket(
      tickets[0].ticket,
      'alice',
      'device-alice',
      'charlie',
    );
    expect(bad).toBe(false);
  });

  it('rejects modified ticket bytes', async () => {
    mockConnectionRepo.find.mockResolvedValueOnce([
      { requesterUserId: 'alice', recipientUserId: 'bob', status: 'ACCEPTED' },
    ]);
    const tickets = await service.issueOfflineCallTickets('alice', 'device-alice');
    const tampered = tickets[0].ticket.slice(0, -4) + 'XXXX';
    expect(
      service.verifyOfflineCallTicket(tampered, 'alice', 'device-alice', 'bob'),
    ).toBe(false);
  });

  it('rejects expired ticket', async () => {
    const exp = Math.floor(Date.now() / 1000) - 60;
    const nonce = 'abc123';
    const payload = `v${OFFLINE_TRUST_PROTOCOL_VERSION}:alice:device-alice:bob:${exp}:${nonce}`;
    const sig = require('crypto')
      .createHmac('sha256', process.env.EPHEMERAL_SIGNING_SECRET!)
      .update(`call:${payload}`)
      .digest('base64url');
    const ticket = Buffer.from(`${payload}:${sig}`).toString('base64url');
    expect(service.verifyOfflineCallTicket(ticket, 'alice', 'device-alice', 'bob')).toBe(false);
  });
});
