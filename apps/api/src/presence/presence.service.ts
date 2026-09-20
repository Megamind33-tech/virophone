import { Injectable } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';
import { BlocksService } from '../blocks/blocks.service';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Profile } from '../database/entities/profile.entity';
import { UsersService } from '../users/users.service';
import { VisibilityService } from '../users/visibility.service';

export type PresenceState = 'OFFLINE' | 'ONLINE' | 'LOCAL' | 'BUSY';

const PRESENCE_TTL_SECONDS = 120;
const REDIS_PREFIX = 'presence:';

@Injectable()
export class PresenceService {
  constructor(
    private readonly redis: RedisService,
    private readonly blocksService: BlocksService,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    private readonly usersService: UsersService,
    private readonly visibility: VisibilityService,
  ) {}

  async setPresence(userId: string, state: PresenceState): Promise<void> {
    await this.redis.setJson(`${REDIS_PREFIX}${userId}`, { state, updatedAt: Date.now() }, PRESENCE_TTL_SECONDS);
    // "Last seen" is the last time they were here at all.
    await this.usersService.touchLastSeen(userId);
  }

  async getPresenceForAuthorizedViewer(
    viewerId: string,
    targetUserId: string,
  ): Promise<{ state: PresenceState; lastSeenAt?: string | null } | null> {
    if (viewerId === targetUserId) {
      const self = await this.redis.getJson<{ state: PresenceState }>(`${REDIS_PREFIX}${targetUserId}`);
      return self ? { state: self.state } : { state: 'OFFLINE' };
    }

    if (await this.blocksService.isBlocked(viewerId, targetUserId)) {
      return { state: 'OFFLINE' };
    }

    const profile = await this.profileRepo.findOne({ where: { userId: targetUserId } });
    const showLastSeen = profile ? await this.visibility.canSeeLastSeen(viewerId, profile) : false;
    const lastSeenAt = showLastSeen ? profile?.lastSeenAt?.toISOString() ?? null : null;
    const record = await this.redis.getJson<{ state: PresenceState }>(`${REDIS_PREFIX}${targetUserId}`);
    if (!record) {
      return { state: 'OFFLINE', lastSeenAt };
    }
    // Someone who hides their last seen is not shown as online either —
    // otherwise the setting only half works.
    return { state: showLastSeen ? record.state : 'OFFLINE', lastSeenAt };
  }

  async clearPresence(userId: string): Promise<void> {
    await this.redis.del(`${REDIS_PREFIX}${userId}`);
  }
}
