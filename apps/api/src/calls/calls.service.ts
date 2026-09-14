import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Call } from '../database/entities/call.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';
import { BlocksService } from '../blocks/blocks.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import type { CallAuthorizeResponse } from '@viro-reach/shared-types';

@Injectable()
export class CallsService {
  constructor(
    @InjectRepository(Call) private readonly callRepo: Repository<Call>,
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    private readonly blocksService: BlocksService,
  ) {}

  async authorize(
    callerId: string,
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

    const routeType = preferredRoute || 'INTERNET_P2P';
    const call = this.callRepo.create({
      callerUserId: callerId,
      calleeUserId: targetUserId,
      status: 'INITIATED',
      routeType,
    });
    await this.callRepo.save(call);

    const expiresAt = new Date(Date.now() + 5 * 60 * 1000);

    return {
      callId: call.id,
      authorized: true,
      expiresAt: expiresAt.toISOString(),
      routeType: routeType as CallAuthorizeResponse['routeType'],
      sessionMaterial: {
        callId: call.id,
        signalingUrl: process.env.FLEXISIP_DOMAIN || 'localhost',
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
    return this.callRepo.save(call);
  }

  private async isCallAllowed(callerId: string, targetUserId: string): Promise<boolean> {
    // Condition A: phone contact match
    const contactMatch = await this.matchRepo.findOne({
      where: { userId: callerId, matchedUserId: targetUserId },
    });
    if (contactMatch) return true;

    // Condition C: accepted Viro connection
    const connection = await this.connectionRepo.findOne({
      where: [
        { requesterUserId: callerId, recipientUserId: targetUserId, status: 'ACCEPTED' },
        { requesterUserId: targetUserId, recipientUserId: callerId, status: 'ACCEPTED' },
      ],
    });
    if (connection) return true;

    // Condition B: exact Viro ID with privacy setting
    const targetProfile = await this.profileRepo.findOne({ where: { userId: targetUserId } });
    if (targetProfile?.allowCallsFromViroId === 'EXACT_ID_ALLOWED') {
      return true;
    }

    return false;
  }
}
