import { SubscriptionsService } from './subscriptions.service';

describe('SubscriptionsService', () => {
  const free = {
    id: 'plan-free',
    name: 'Free',
    description: 'Free plan',
    isActive: true,
  };
  const plus = {
    id: 'plan-plus',
    name: 'Plus',
    description: 'Plus plan',
    isActive: true,
  };

  const planRepo = {
    count: jest.fn(),
    find: jest.fn(),
    findOne: jest.fn(),
    create: jest.fn((x) => x),
    save: jest.fn(async (x) => x),
  };
  const subRepo = {
    findOne: jest.fn(),
    find: jest.fn(),
    create: jest.fn((x) => x),
    save: jest.fn(async (x) => x),
  };

  let service: SubscriptionsService;

  beforeEach(() => {
    jest.clearAllMocks();
    planRepo.count.mockResolvedValue(2);
    planRepo.find.mockResolvedValue([free, plus]);
    planRepo.findOne.mockImplementation(async ({ where }: any) => {
      if (where?.name === 'Free') return free;
      if (where?.id === plus.id) return plus;
      if (where?.id === free.id) return free;
      return null;
    });
    subRepo.findOne.mockResolvedValue(null);
    subRepo.find.mockResolvedValue([]);
    service = new SubscriptionsService(planRepo as any, subRepo as any);
  });

  it('lists active plans', async () => {
    const plans = await service.listPlans();
    expect(plans).toHaveLength(2);
  });

  it('defaults to Free when user has no subscription row', async () => {
    const mine = await service.getMine('user-1');
    expect(mine.planName).toBe('Free');
    expect(mine.isDefault).toBe(true);
  });

  it('selects Plus and cancels prior active subscriptions', async () => {
    subRepo.find.mockResolvedValue([{ id: 'old', status: 'ACTIVE', userId: 'user-1' }]);
    subRepo.findOne.mockResolvedValue({
      id: 'new',
      userId: 'user-1',
      planId: plus.id,
      status: 'ACTIVE',
      expiresAt: new Date('2026-10-16T00:00:00Z'),
    });
    const mine = await service.selectPlan('user-1', plus.id);
    expect(subRepo.save).toHaveBeenCalled();
    expect(mine.planName).toBe('Plus');
    expect(mine.isDefault).toBe(false);
  });
});
