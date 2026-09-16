import { Injectable } from '@nestjs/common';
import { v4 as uuidv4 } from 'uuid';
import { RedisService } from '../redis/redis.service';

export interface ConferenceParticipant {
  userId: string;
  deviceId: string;
}

interface RoomMeta {
  roomId: string;
  ownerUserId: string;
  allowed: string[];
  title?: string;
  createdAt: number;
}

const TTL_SECONDS = 6 * 60 * 60;

/**
 * Redis-backed conference rooms for small-group mesh calls. Each participant
 * establishes a WebRTC connection to every other participant; the server only
 * relays signaling and tracks membership (no media). Suitable for small N;
 * large groups would move to an SFU (see checklist D4).
 */
@Injectable()
export class ConferenceService {
  constructor(private readonly redis: RedisService) {}

  private metaKey(roomId: string) {
    return `conf:${roomId}:meta`;
  }
  private membersKey(roomId: string) {
    return `conf:${roomId}:members`;
  }

  async createRoom(
    ownerUserId: string,
    inviteeUserIds: string[] = [],
    title?: string,
  ): Promise<{ roomId: string; allowed: string[] }> {
    const roomId = uuidv4();
    const allowed = Array.from(new Set([ownerUserId, ...inviteeUserIds]));
    const meta: RoomMeta = {
      roomId,
      ownerUserId,
      allowed,
      title,
      createdAt: Date.now(),
    };
    await this.redis.setJson(this.metaKey(roomId), meta, TTL_SECONDS);
    return { roomId, allowed };
  }

  async getMeta(roomId: string): Promise<RoomMeta | null> {
    return this.redis.getJson<RoomMeta>(this.metaKey(roomId));
  }

  async canJoin(roomId: string, userId: string): Promise<boolean> {
    const meta = await this.getMeta(roomId);
    if (!meta) return false;
    // Empty allow-list => open room; otherwise must be invited.
    return meta.allowed.length === 0 || meta.allowed.includes(userId);
  }

  async join(
    roomId: string,
    userId: string,
    deviceId: string,
  ): Promise<ConferenceParticipant[]> {
    await this.redis.sAdd(
      this.membersKey(roomId),
      `${userId}:${deviceId}`,
      TTL_SECONDS,
    );
    return this.members(roomId);
  }

  async leave(roomId: string, userId: string, deviceId: string): Promise<void> {
    await this.redis.sRem(this.membersKey(roomId), `${userId}:${deviceId}`);
  }

  async members(roomId: string): Promise<ConferenceParticipant[]> {
    const raw = await this.redis.sMembers(this.membersKey(roomId));
    return raw.map((entry) => {
      const idx = entry.indexOf(':');
      return { userId: entry.slice(0, idx), deviceId: entry.slice(idx + 1) };
    });
  }

  async isMember(roomId: string, deviceId: string): Promise<boolean> {
    const members = await this.members(roomId);
    return members.some((m) => m.deviceId === deviceId);
  }
}
