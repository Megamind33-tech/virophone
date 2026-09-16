import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, IsNull } from 'typeorm';
import { Call } from '../database/entities/call.entity';
import { CallQuality } from '../database/entities/call-quality.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Device } from '../database/entities/device.entity';
import { BlocksService } from '../blocks/blocks.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import type { CallAuthorizeResponse } from '@viro-reach/shared-types';
import { CallSessionService } from './call-session.service';
import { RedisService } from '../redis/redis.service';
import { PushService } from '../push/push.service';
import { OfflineTrustService } from '../offline-trust/offline-trust.service';
import { isUserUuid } from './call-target.util';

export interface CallQualityInput {
  latency?: number;
  jitter?: number;
  packetLoss?: number;
  bitrate?: number;
  codec?: string;
  route?: string;
  relayed?: boolean;
}

@Injectable()
export class CallsService {
  constructor(
    @InjectRepository(Call) private readonly callRepo: Repository<Call>,
    @InjectRepository(CallQuality) private readonly callQualityRepo: Repository<CallQuality>,
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(PhoneIdentity) private readonly phoneRepo: Repository<PhoneIdentity>,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    private readonly blocksService: BlocksService,
    private readonly callSessionService: CallSessionService,
    private readonly redis: RedisService,
    private readonly pushService: PushService,
    private readonly offlineTrustService: OfflineTrustService,
  ) {}

  async authorize(
    callerId: string,
    callerDeviceId: string,
    targetUserId: string,
    preferredRoute?: string,
    offlineTicket?: string,
  ): Promise<CallAuthorizeResponse> {
    if (!isUserUuid(targetUserId)) {
      throw new ViroException(
        'INVALID_TARGET',
        'targetUserId must be a valid user UUID. Resolve phone numbers via contact discovery first.',
        HttpStatus.BAD_REQUEST,
      );
    }

    if (callerId === targetUserId) {
      throw new ViroException('CALL_NOT_AUTHORIZED', 'Cannot call yourself.', HttpStatus.FORBIDDEN);
    }

    if (await this.blocksService.isBlocked(callerId, targetUserId)) {
      throw new ViroException(
        'CALL_TARGET_UNAVAILABLE',
        'This person is currently unavailable.',
        HttpStatus.NOT_FOUND,
      );
    }

    const allowed = await this.isCallAllowed(callerId, targetUserId);
    if (!allowed) {
      // Fall back to a signed offline call ticket, which proves a relationship
      // that was authorized previously (e.g. established offline / on LAN).
      const ticketOk =
        !!offlineTicket &&
        this.offlineTrustService.verifyOfflineCallTicket(
          offlineTicket,
          callerId,
          callerDeviceId,
          targetUserId,
        );
      if (!ticketOk) {
        throw new ViroException(
          'CALL_NOT_AUTHORIZED',
          'You are not authorized to call this person.',
          HttpStatus.FORBIDDEN,
        );
      }
    }

    const calleeDeviceIds = await this.resolveCalleeDeviceIds(targetUserId);
    if (!calleeDeviceIds.length) {
      throw new ViroException(
        'CALL_TARGET_UNAVAILABLE',
        'This person is currently unavailable.',
        HttpStatus.NOT_FOUND,
      );
    }
    const calleeDeviceId = calleeDeviceIds[0];

    const routeType = preferredRoute || 'INTERNET_P2P';
    const call = this.callRepo.create({
      callerUserId: callerId,
      calleeUserId: targetUserId,
      status: 'INITIATED',
      routeType,
    });
    await this.callRepo.save(call);

    await this.callSessionService.createSession({
      callId: call.id,
      callerUserId: callerId,
      calleeUserId: targetUserId,
      callerDeviceId,
      calleeDeviceId,
      calleeDeviceIds,
    });

    const expiresAt = new Date(Date.now() + 5 * 60 * 1000);
    const apiBase = process.env.API_BASE_URL || 'http://localhost:3001';
    const wsBase = apiBase.replace(/^http/, 'ws');
    const signalingUrl = `${wsBase}/api/v1/signaling/ws`;

    // Wake the callee's devices via push so an incoming call reaches them even
    // when the app is backgrounded / the WebSocket is not currently connected.
    const callerName = await this.callerDisplayName(callerId);
    await this.pushService.sendToUser(targetUserId, {
      title: 'Incoming Viro call',
      body: callerName ? `${callerName} is calling` : 'Incoming call',
      highPriority: true,
      data: {
        type: 'incoming_call',
        callId: call.id,
        callerUserId: callerId,
        callerDeviceId,
        calleeDeviceId,
        routeType,
        signalingUrl,
      },
    });

    return {
      callId: call.id,
      authorized: true,
      expiresAt: expiresAt.toISOString(),
      routeType: routeType as CallAuthorizeResponse['routeType'],
      sessionMaterial: {
        callId: call.id,
        signalingUrl,
        calleeDeviceId,
        callerDeviceId,
        calleeDeviceIds: calleeDeviceIds.join(','),
        routeType,
      },
    };
  }

  /** Marks the call as ringing (callee alerted) — driven from the signaling gateway. */
  async markRinging(callId: string): Promise<void> {
    await this.callRepo.update(
      { id: callId, status: 'INITIATED' },
      { status: 'RINGING' },
    );
  }

  /** Marks the call active with an answered timestamp — driven from call.answer. */
  async markActive(callId: string): Promise<void> {
    const call = await this.callRepo.findOne({ where: { id: callId } });
    if (!call || call.status === 'ENDED') return;
    call.status = 'ACTIVE';
    if (!call.answeredAt) call.answeredAt = new Date();
    await this.callRepo.save(call);
  }

  /** Persists a call-quality sample (from POST /calls/:id/events). */
  async recordQuality(callId: string, input: CallQualityInput): Promise<void> {
    const call = await this.callRepo.findOne({ where: { id: callId } });
    if (!call) return;
    const existing = await this.callQualityRepo.findOne({ where: { callId } });
    const row = existing ?? this.callQualityRepo.create({ callId });
    if (input.latency !== undefined) row.latency = input.latency;
    if (input.jitter !== undefined) row.jitter = input.jitter;
    if (input.packetLoss !== undefined) row.packetLoss = input.packetLoss;
    if (input.bitrate !== undefined) row.bitrate = input.bitrate;
    if (input.codec !== undefined) row.codec = input.codec;
    if (input.route !== undefined) row.route = input.route;
    if (input.relayed !== undefined) row.relayed = input.relayed;
    await this.callQualityRepo.save(row);
  }

  /** Returns the recent call history for a user (as caller or callee). */
  async history(userId: string, limit = 50) {
    return this.callRepo.find({
      where: [{ callerUserId: userId }, { calleeUserId: userId }],
      order: { startedAt: 'DESC' },
      take: Math.min(Math.max(limit, 1), 200),
    });
  }

  async getCallerPresentation(userId: string): Promise<{
    callerPhoneE164?: string;
    callerDisplayName?: string;
  }> {
    const [profile, phoneIdentity] = await Promise.all([
      this.profileRepo.findOne({ where: { userId } }),
      this.phoneRepo.findOne({ where: { userId, status: 'VERIFIED' } }),
    ]);
    const displayName = profile?.displayName?.trim();
    return {
      callerPhoneE164: phoneIdentity?.phoneE164,
      callerDisplayName: displayName && displayName.length > 0 ? displayName : undefined,
    };
  }

  private async callerDisplayName(userId: string): Promise<string | null> {
    const presentation = await this.getCallerPresentation(userId);
    return presentation.callerDisplayName ?? null;
  }

  async endCall(callId: string, userId: string) {
    const call = await this.callRepo.findOne({ where: { id: callId } });
    if (!call) {
      throw new ViroException('NOT_FOUND', 'Call not found.', HttpStatus.NOT_FOUND);
    }
    if (call.callerUserId !== userId && call.calleeUserId !== userId) {
      throw new ViroException('FORBIDDEN', 'Not authorized.', HttpStatus.FORBIDDEN);
    }
    call.status = 'ENDED';
    call.endedAt = new Date();
    await this.callSessionService.endSession(callId);
    return this.callRepo.save(call);
  }

  private async resolveCalleeDeviceIds(userId: string): Promise<string[]> {
    const online = await this.redis.sMembers(`ws:userdevices:${userId}`);
    const valid: string[] = [];
    for (const id of online) {
      const device = await this.deviceRepo.findOne({
        where: { id, userId, revokedAt: IsNull() },
      });
      if (device) valid.push(device.id);
    }
    if (valid.length) return Array.from(new Set(valid));

    const devices = await this.deviceRepo.find({
      where: { userId, revokedAt: IsNull() },
      order: { lastSeenAt: 'DESC' },
      take: 8,
    });
    return devices.map((d) => d.id);
  }

  private async isCallAllowed(callerId: string, targetUserId: string): Promise<boolean> {
    const contactMatch = await this.matchRepo.findOne({
      where: { userId: callerId, matchedUserId: targetUserId },
    });
    if (contactMatch) return true;

    const connection = await this.connectionRepo.findOne({
      where: [
        { requesterUserId: callerId, recipientUserId: targetUserId, status: 'ACCEPTED' },
        { requesterUserId: targetUserId, recipientUserId: callerId, status: 'ACCEPTED' },
      ],
    });
    if (connection) return true;

    const targetProfile = await this.profileRepo.findOne({ where: { userId: targetUserId } });
    if (targetProfile?.allowCallsFromViroId === 'EXACT_ID_ALLOWED') {
      return true;
    }

    return false;
  }
}
