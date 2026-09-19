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
  /**
   * People added to a call already in progress ("add caller"). Kept
   * separate from callerDeviceId/calleeDeviceIds so that ringing,
   * first-to-answer-wins and the busy fan-out keep treating the call as
   * the 1:1 it started as — an invitee accepting must never make the
   * original callee's device look like a losing second device.
   */
  invitedUserIds?: string[];
  invitedDeviceIds?: string[];
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

  /**
   * Adds a third party to a call already under way. Returns their device ids
   * so the caller can ring them, or null if the session is gone.
   */
  async addInvitee(
    callId: string,
    userId: string,
    deviceIds: string[],
  ): Promise<string[] | null> {
    const session = await this.getSession(callId);
    if (!session) return null;
    session.invitedUserIds = Array.from(
      new Set([...(session.invitedUserIds ?? []), userId]),
    );
    session.invitedDeviceIds = Array.from(
      new Set([...(session.invitedDeviceIds ?? []), ...deviceIds]),
    );
    await this.redis.setJson(`${PREFIX}${callId}`, session, TTL_SECONDS);
    return session.invitedDeviceIds;
  }

  /** Every device that is legitimately on this call, invitees included. */
  participantDevicesOf(session: CallSession): string[] {
    return Array.from(
      new Set([
        session.callerDeviceId,
        ...this.calleeDevicesOf(session),
        ...(session.invitedDeviceIds ?? []),
      ]),
    );
  }

  async isParticipant(callId: string, userId: string, deviceId: string): Promise<boolean> {
    const session = await this.getSession(callId);
    if (!session || session.expiresAt < Date.now()) return false;
    const calleeDevices = session.calleeDeviceIds?.length
      ? session.calleeDeviceIds
      : [session.calleeDeviceId];
    const caller = session.callerUserId === userId && session.callerDeviceId === deviceId;
    const callee = session.calleeUserId === userId && calleeDevices.includes(deviceId);
    // Someone added mid-call is as much a participant as the original
    // two — this is what lets them fetch a LiveKit token for the room.
    const invited =
      (session.invitedUserIds ?? []).includes(userId) &&
      (session.invitedDeviceIds ?? []).includes(deviceId);
    return caller || callee || invited;
  }

  calleeDevicesOf(session: CallSession): string[] {
    return session.calleeDeviceIds?.length
      ? session.calleeDeviceIds
      : [session.calleeDeviceId];
  }

  async markAnswered(callId: string, deviceId: string): Promise<string[]> {
    const session = await this.getSession(callId);
    if (!session) return [];
    // An invitee accepting is a third party joining a call that is already
    // answered — not one of the callee's devices winning the race. Recording
    // them as THE answering device would re-point all routing at them, and
    // returning the callee's devices here would send the original callee a
    // "call.busy" and drop them off their own call.
    const isInvitee = (session.invitedDeviceIds ?? []).includes(deviceId);
    if (isInvitee) return [];
    session.answeredDeviceId = deviceId;
    await this.redis.setJson(`${PREFIX}${callId}`, session, TTL_SECONDS);
    return this.calleeDevicesOf(session).filter((id) => id !== deviceId);
  }

  async endSession(callId: string): Promise<void> {
    await this.updateState(callId, 'ENDED');
    await this.redis.del(`${PREFIX}${callId}`);
  }
}
