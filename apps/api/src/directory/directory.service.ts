import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Profile } from '../database/entities/profile.entity';
import { Block } from '../database/entities/block.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { normalizeViroId, formatViroId } from '../common/utils/viro-id.util';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import type { PublicProfile } from '@viro-reach/shared-types';

@Injectable()
export class DirectoryService {
  constructor(
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
  ) {}

  /**
   * Exact Viro ID lookup only. No wildcard/partial search.
   */
  async exactLookup(requesterId: string, viroId: string): Promise<PublicProfile | null> {
    const normalized = normalizeViroId(viroId);
    if (!normalized) {
      throw new ViroException('VALIDATION_ERROR', 'Invalid Viro ID format.', HttpStatus.BAD_REQUEST);
    }

    const profile = await this.profileRepo.findOne({
      where: { viroIdNormalized: normalized },
    });

    if (!profile) {
      return null;
    }

    if (profile.userId === requesterId) {
      return null;
    }

    // Check blocking
    const blocked = await this.blockRepo.findOne({
      where: [
        { blockerUserId: profile.userId, blockedUserId: requesterId },
        { blockerUserId: requesterId, blockedUserId: profile.userId },
      ],
    });
    if (blocked) {
      return null;
    }

    return {
      userId: profile.userId,
      displayName: profile.displayName,
      avatarUrl: profile.avatarUrl,
      viroId: profile.viroId || formatViroId(normalized),
    };
  }
}
