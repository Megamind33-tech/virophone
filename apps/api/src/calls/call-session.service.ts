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
  }): Promise<CallSession> {
    const session: CallSession = {
      ...params,
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
    const caller = session.callerUserId === userId && session.callerDeviceId === deviceId;
    const callee = session.calleeUserId === userId && session.calleeDeviceId === deviceId;
    return caller || callee;
  }

  async endSession(callId: string): Promise<void> {
    await this.updateState(callId, 'ENDED');
    await this.redis.del(`${PREFIX}${callId}`);
  }
}
