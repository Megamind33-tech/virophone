import { Injectable } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';

export type CallSessionState =
  | 'INITIATED'
  | 'RINGING'
  | 'CONNECTING'
  | 'ACTIVE'
  | 'ENDED'
  | 'FAILED';

export interface CallSession {
  callId: string;
  callerUserId: string;
  calleeUserId: string;
  callerDeviceId: string;
  calleeDeviceId: string;
  calleeDeviceIds: string[];
  answeredDeviceId?: string;
  state: CallSessionState;
  expiresAt: number;
  createdAt: number;
}

const PREFIX = 'call:session:';
const TTL_SECONDS = 600;

@Injectable()
export class CallSessionService {
  constructor(private readonly redis: RedisService) {}

  async createSession(params: {
    callId: string;
    callerUserId: string;
    calleeUserId: string;
    callerDeviceId: string;
    calleeDeviceId: string;
    calleeDeviceIds?: string[];
  }): Promise<CallSession> {
    const calleeDeviceIds = Array.from(
      new Set(params.calleeDeviceIds?.length ? params.calleeDeviceIds : [params.calleeDeviceId]),
    );
    const session: CallSession = {
      ...params,
      calleeDeviceIds,
      state: 'INITIATED',
      createdAt: Date.now(),
      expiresAt: Date.now() + TTL_SECONDS * 1000,
    };
    await this.redis.setJson(`${PREFIX}${params.callId}`, session, TTL_SECONDS);
    return session;
  }

  async getSession(callId: string): Promise<CallSession | null> {
    return this.redis.getJson<CallSession>(`${PREFIX}${callId}`);
  }

  async updateState(callId: string, state: CallSessionState): Promise<void> {
    const session = await this.getSession(callId);
    if (!session) return;
    session.state = state;
    await this.redis.setJson(`${PREFIX}${callId}`, session, TTL_SECONDS);
  }

  async isParticipant(callId: string, userId: string, deviceId: string): Promise<boolean> {
    const session = await this.getSession(callId);
    if (!session || session.expiresAt < Date.now()) return false;
    const calleeDevices = session.calleeDeviceIds?.length
      ? session.calleeDeviceIds
      : [session.calleeDeviceId];
    const caller = session.callerUserId === userId && session.callerDeviceId === deviceId;
    const callee = session.calleeUserId === userId && calleeDevices.includes(deviceId);
    return caller || callee;
  }

  calleeDevicesOf(session: CallSession): string[] {
    return session.calleeDeviceIds?.length
      ? session.calleeDeviceIds
      : [session.calleeDeviceId];
  }

  async markAnswered(callId: string, deviceId: string): Promise<string[]> {
    const session = await this.getSession(callId);
    if (!session) return [];
    session.answeredDeviceId = deviceId;
    await this.redis.setJson(`${PREFIX}${callId}`, session, TTL_SECONDS);
    return this.calleeDevicesOf(session).filter((id) => id !== deviceId);
  }

  async endSession(callId: string): Promise<void> {
    await this.updateState(callId, 'ENDED');
    await this.redis.del(`${PREFIX}${callId}`);
  }
}
