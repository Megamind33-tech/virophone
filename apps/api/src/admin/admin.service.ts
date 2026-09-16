import { Injectable, HttpStatus } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { ILike, In, Repository } from 'typeorm';
import { User } from '../database/entities/user.entity';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Device } from '../database/entities/device.entity';
import { SecurityEvent } from '../database/entities/security-event.entity';
import { ViroException } from '../common/exceptions/viro.exception';

@Injectable()
export class AdminService {
  constructor(
    @InjectRepository(User) private readonly users: Repository<User>,
    @InjectRepository(Profile) private readonly profiles: Repository<Profile>,
    @InjectRepository(PhoneIdentity) private readonly phones: Repository<PhoneIdentity>,
    @InjectRepository(Device) private readonly devices: Repository<Device>,
    @InjectRepository(SecurityEvent) private readonly events: Repository<SecurityEvent>,
  ) {}

  async listUsers(q?: string, limit = 50) {
    const take = Math.min(Math.max(limit, 1), 200);
    if (q && q.trim()) {
      const term = `%${q.trim()}%`;
      const phones = await this.phones.find({ where: { phoneE164: ILike(term) }, take });
      const profiles = await this.profiles.find({
        where: [{ displayName: ILike(term) }, { viroId: ILike(term) }],
        take,
      });
      const ids = Array.from(
        new Set([...phones.map((p) => p.userId), ...profiles.map((p) => p.userId)]),
      ).slice(0, take);
      if (!ids.length) return [];
      const users = await this.users.find({ where: { id: In(ids) } });
      return this.summaries(users);
    }
    const users = await this.users.find({ order: { createdAt: 'DESC' }, take });
    return this.summaries(users);
  }

  async getUser(id: string) {
    const user = await this.users.findOne({ where: { id } });
    if (!user) {
      throw new ViroException('NOT_FOUND', 'User not found.', HttpStatus.NOT_FOUND);
    }
    const [profile, phone, devices] = await Promise.all([
      this.profiles.findOne({ where: { userId: id } }),
      this.phones.findOne({ where: { userId: id } }),
      this.devices.find({ where: { userId: id }, order: { lastSeenAt: 'DESC' } }),
    ]);
    return {
      id: user.id,
      status: user.status,
      adminRole: user.adminRole,
      createdAt: user.createdAt,
      displayName: profile?.displayName ?? '',
      viroId: profile?.viroId ?? null,
      phoneE164: phone?.phoneE164 ?? null,
      devices: devices.map((d) => ({
        id: d.id,
        platform: d.platform,
        lastSeenAt: d.lastSeenAt,
        revokedAt: d.revokedAt,
      })),
    };
  }

  async setStatus(id: string, status: 'ACTIVE' | 'SUSPENDED') {
    const user = await this.users.findOne({ where: { id } });
    if (!user) {
      throw new ViroException('NOT_FOUND', 'User not found.', HttpStatus.NOT_FOUND);
    }
    if (user.status === 'DELETED') {
      throw new ViroException('FORBIDDEN', 'Deleted accounts cannot be restored here.', HttpStatus.FORBIDDEN);
    }
    user.status = status;
    await this.users.save(user);
    if (status === 'SUSPENDED') {
      await this.devices
        .createQueryBuilder()
        .update()
        .set({ revokedAt: () => 'NOW()' })
        .where('user_id = :id AND revoked_at IS NULL', { id })
        .execute();
    }
    return this.getUser(id);
  }

  async securityEvents(limit = 50) {
    return this.events.find({
      order: { createdAt: 'DESC' },
      take: Math.min(Math.max(limit, 1), 200),
    });
  }

  private async summaries(users: User[]) {
    const ids = users.map((u) => u.id);
    const profileRows = ids.length
      ? await this.profiles
          .createQueryBuilder('p')
          .where('p.user_id IN (:...ids)', { ids })
          .getMany()
      : [];
    const byUser = new Map(profileRows.map((p) => [p.userId, p]));
    return users.map((u) => ({
      id: u.id,
      status: u.status,
      adminRole: u.adminRole,
      createdAt: u.createdAt,
      displayName: byUser.get(u.id)?.displayName ?? '',
      viroId: byUser.get(u.id)?.viroId ?? null,
    }));
  }
}
