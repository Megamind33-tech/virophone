import { Injectable } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';
import { BlocksService } from '../blocks/blocks.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

export interface EphemeralRegistration {
  ephemeralId: string;
  userId: string;
  deviceId: string;
  expiresAt: number;
}

export interface EphemeralResolveResult {
  authorized: boolean;
  userId?: string;
  displayName?: string;
  localContactHint?: string;
}

const EPHEMERAL_TTL_SECONDS = parseInt(process.env.EPHEMERAL_TTL_SECONDS || '900', 10); // 15 min
const REDIS_PREFIX = 'ephemeral:';

@Injectable()
export class DiscoveryService {
  constructor(
    private readonly redis: RedisService,
    private readonly blocksService: BlocksService,
  ) {}

  async registerEphemeral(
    userId: string,
    deviceId: string,
    ephemeralId: string,
  ): Promise<{ expiresAt: string }> {
    if (!ephemeralId.startsWith('vr1_') || ephemeralId.length < 20) {
      throw new ViroException('VALIDATION_ERROR', 'Invalid ephemeral ID format.', HttpStatus.BAD_REQUEST);
    }

    const expiresAt = Date.now() + EPHEMERAL_TTL_SECONDS * 1000;
    const record: EphemeralRegistration = {
      ephemeralId,
      userId,
      deviceId,
      expiresAt,
    };

    await this.redis.setJson(`${REDIS_PREFIX}${ephemeralId}`, record, EPHEMERAL_TTL_SECONDS);
    // Device can rotate ID; map device to current ephemeral for cleanup only (short TTL)
    await this.redis.setJson(`${REDIS_PREFIX}device:${deviceId}`, { ephemeralId }, EPHEMERAL_TTL_SECONDS);

    return { expiresAt: new Date(expiresAt).toISOString() };
  }

  /**
   * Resolve ephemeral ID for authorized requester only.
   * Does not expose full profile — minimal data for local contact mapping.
   */
  async resolveEphemeral(
    requesterUserId: string,
    ephemeralId: string,
    authorizedUserIds: string[],
  ): Promise<EphemeralResolveResult> {
    const record = await this.redis.getJson<EphemeralRegistration>(`${REDIS_PREFIX}${ephemeralId}`);
    if (!record || record.expiresAt < Date.now()) {
      return { authorized: false };
    }

    if (record.userId === requesterUserId) {
      return { authorized: false };
    }

    if (await this.blocksService.isBlocked(requesterUserId, record.userId)) {
      return { authorized: false };
    }

    if (!authorizedUserIds.includes(record.userId)) {
      return { authorized: false };
    }

    return {
      authorized: true,
      userId: record.userId,
    };
  }
}
