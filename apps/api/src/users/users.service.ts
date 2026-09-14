import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { normalizeViroId, formatViroId } from '../common/utils/viro-id.util';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class UsersService {
  constructor(
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(PhoneIdentity) private readonly phoneRepo: Repository<PhoneIdentity>,
  ) {}

  async getMe(userId: string) {
    const profile = await this.profileRepo.findOne({ where: { userId } });
    const phone = await this.phoneRepo.findOne({ where: { userId, status: 'VERIFIED' } });
    if (!profile) {
      throw new ViroException('NOT_FOUND', 'Profile not found.', HttpStatus.NOT_FOUND);
    }
    return {
      userId,
      phoneE164: phone?.phoneE164 || '',
      displayName: profile.displayName,
      avatarUrl: profile.avatarUrl,
      viroId: profile.viroId,
      allowCallsFromViroId: profile.allowCallsFromViroId,
    };
  }

  async updateMe(userId: string, updates: {
    displayName?: string;
    avatarUrl?: string | null;
    viroId?: string;
    allowCallsFromViroId?: string;
  }) {
    const profile = await this.profileRepo.findOne({ where: { userId } });
    if (!profile) {
      throw new ViroException('NOT_FOUND', 'Profile not found.', HttpStatus.NOT_FOUND);
    }

    if (updates.displayName !== undefined) profile.displayName = updates.displayName;
    if (updates.avatarUrl !== undefined) profile.avatarUrl = updates.avatarUrl;
    if (updates.allowCallsFromViroId !== undefined) {
      profile.allowCallsFromViroId = updates.allowCallsFromViroId;
    }

    if (updates.viroId !== undefined) {
      const normalized = normalizeViroId(updates.viroId);
      if (!normalized) {
        throw new ViroException('VALIDATION_ERROR', 'Invalid Viro ID.', HttpStatus.BAD_REQUEST);
      }
      const existing = await this.profileRepo.findOne({ where: { viroIdNormalized: normalized } });
      if (existing && existing.userId !== userId) {
        throw new ViroException('DUPLICATE_VIRO_ID', 'Viro ID already taken.', HttpStatus.CONFLICT);
      }
      profile.viroId = formatViroId(normalized);
      profile.viroIdNormalized = normalized;
    }

    await this.profileRepo.save(profile);
    return this.getMe(userId);
  }
}
