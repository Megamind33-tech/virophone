import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, IsNull } from 'typeorm';
import { Call } from '../database/entities/call.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';
import { Device } from '../database/entities/device.entity';
import { BlocksService } from '../blocks/blocks.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import type { CallAuthorizeResponse } from '@viro-reach/shared-types';
import { CallSessionService } from './call-session.service';
import { RedisService } from '../redis/redis.service';

@Injectable()
export class CallsService {
  constructor(
    @InjectRepository(Call) private readonly callRepo: Repository<Call>,
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    private readonly blocksService: BlocksService,
    private readonly callSessionService: CallSessionService,
    private readonly redis: RedisService,
  ) {}

  async authorize(
    callerId: string,
    callerDeviceId: string,
    targetUserId: string,
    preferredRoute?: string,
  ): Promise<CallAuthorizeResponse> {
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
      throw new ViroException(
        'CALL_NOT_AUTHORIZED',
        'You are not authorized to call this person.',
        HttpStatus.FORBIDDEN,
      );
    }

    const calleeDeviceId = await this.resolveCalleeDeviceId(targetUserId);
    if (!calleeDeviceId) {
      throw new ViroException(
        'CALL_TARGET_UNAVAILABLE',
        'This person is currently unavailable.',
        HttpStatus.NOT_FOUND,
      );
    }

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
    });

    const expiresAt = new Date(Date.now() + 5 * 60 * 1000);
    const apiBase = process.env.API_BASE_URL || 'http://localhost:3001';
    const wsBase = apiBase.replace(/^http/, 'ws');
    const signalingUrl = `${wsBase}/api/v1/signaling/ws`;

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
        routeType,
      },
    };
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

  private async resolveCalleeDeviceId(userId: string): Promise<string | null> {
    const wsConn = await this.redis.getJson<{ deviceId: string }>(`ws:user:${userId}`);
    if (wsConn?.deviceId) {
      const device = await this.deviceRepo.findOne({
        where: { id: wsConn.deviceId, userId, revokedAt: IsNull() },
      });
      if (device) return device.id;
    }

    const devices = await this.deviceRepo.find({
      where: { userId, revokedAt: IsNull() },
      order: { lastSeenAt: 'DESC' },
      take: 1,
    });
    return devices[0]?.id ?? null;
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
