import { Injectable } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';
import { BlocksService } from '../blocks/blocks.service';

export type PresenceState = 'OFFLINE' | 'ONLINE' | 'LOCAL' | 'BUSY';

const PRESENCE_TTL_SECONDS = 120;
const REDIS_PREFIX = 'presence:';

@Injectable()
export class PresenceService {
  constructor(
    private readonly redis: RedisService,
    private readonly blocksService: BlocksService,
  ) {}

  async setPresence(userId: string, state: PresenceState): Promise<void> {
    await this.redis.setJson(`${REDIS_PREFIX}${userId}`, { state, updatedAt: Date.now() }, PRESENCE_TTL_SECONDS);
  }

  async getPresenceForAuthorizedViewer(
    viewerId: string,
    targetUserId: string,
  ): Promise<{ state: PresenceState } | null> {
    if (viewerId === targetUserId) {
      const self = await this.redis.getJson<{ state: PresenceState }>(`${REDIS_PREFIX}${targetUserId}`);
      return self ? { state: self.state } : { state: 'OFFLINE' };
    }

    if (await this.blocksService.isBlocked(viewerId, targetUserId)) {
      return { state: 'OFFLINE' };
    }

    const record = await this.redis.getJson<{ state: PresenceState }>(`${REDIS_PREFIX}${targetUserId}`);
    if (!record) {
      return { state: 'OFFLINE' };
    }
    return { state: record.state };
  }

  async clearPresence(userId: string): Promise<void> {
    await this.redis.del(`${REDIS_PREFIX}${userId}`);
  }
}
