import { Injectable, HttpStatus } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Plan } from '../database/entities/plan.entity';
import { Subscription } from '../database/entities/subscription.entity';
import { ViroException } from '../common/exceptions/viro.exception';

@Injectable()
export class SubscriptionsService {
  constructor(
    @InjectRepository(Plan) private readonly planRepo: Repository<Plan>,
    @InjectRepository(Subscription) private readonly subRepo: Repository<Subscription>,
  ) {}

  async listPlans() {
    await this.ensureSeedPlans();
    return this.planRepo.find({
      where: { isActive: true },
      order: { name: 'ASC' },
    });
  }

  async getMine(userId: string) {
    await this.ensureSeedPlans();
    const active = await this.subRepo.findOne({
      where: { userId, status: 'ACTIVE' },
      order: { createdAt: 'DESC' },
    });
    if (!active) {
      const free = await this.planRepo.findOne({ where: { name: 'Free', isActive: true } });
      return {
        planId: free?.id ?? null,
        planName: free?.name ?? 'Free',
        description: free?.description ?? null,
        status: 'ACTIVE',
        expiresAt: null as string | null,
        isDefault: true,
      };
    }
    const plan = await this.planRepo.findOne({ where: { id: active.planId } });
    return {
      planId: active.planId,
      planName: plan?.name ?? 'Unknown',
      description: plan?.description ?? null,
      status: active.status,
      expiresAt: active.expiresAt?.toISOString() ?? null,
      isDefault: false,
    };
  }

  /** Stub activate — no payment provider; flips the user's active plan. */
  async selectPlan(userId: string, planId: string) {
    const plan = await this.planRepo.findOne({ where: { id: planId, isActive: true } });
    if (!plan) {
      throw new ViroException('NOT_FOUND', 'Plan not found.', HttpStatus.NOT_FOUND);
    }

    const existing = await this.subRepo.find({ where: { userId, status: 'ACTIVE' } });
    for (const row of existing) {
      row.status = 'CANCELLED';
      await this.subRepo.save(row);
    }

    const expiresAt =
      plan.name === 'Free' ? null : new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);

    const created = this.subRepo.create({
      userId,
      planId: plan.id,
      status: 'ACTIVE',
      expiresAt,
    });
    await this.subRepo.save(created);
    return this.getMine(userId);
  }

  private async ensureSeedPlans() {
    const count = await this.planRepo.count();
    if (count > 0) return;
    await this.planRepo.save([
      this.planRepo.create({
        name: 'Free',
        description: 'Core calling and messaging for everyone on Viro.',
        isActive: true,
      }),
      this.planRepo.create({
        name: 'Plus',
        description: 'Preview Plus plan (billing integration comes later).',
        isActive: true,
      }),
    ]);
  }
}
