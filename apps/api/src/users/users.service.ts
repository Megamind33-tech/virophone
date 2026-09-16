import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { User } from '../database/entities/user.entity';
import { Device } from '../database/entities/device.entity';
import { Session } from '../database/entities/session.entity';
import { Call } from '../database/entities/call.entity';
import { Block } from '../database/entities/block.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { PushToken } from '../database/entities/push-token.entity';
import { ConversationParticipant } from '../database/entities/conversation-participant.entity';
import { Message } from '../database/entities/message.entity';
import { normalizeViroId, formatViroId } from '../common/utils/viro-id.util';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class UsersService {
  constructor(
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(PhoneIdentity) private readonly phoneRepo: Repository<PhoneIdentity>,
    @InjectRepository(User) private readonly userRepo: Repository<User>,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    @InjectRepository(Session) private readonly sessionRepo: Repository<Session>,
    @InjectRepository(Call) private readonly callRepo: Repository<Call>,
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(PushToken) private readonly pushRepo: Repository<PushToken>,
    @InjectRepository(ConversationParticipant)
    private readonly partRepo: Repository<ConversationParticipant>,
    @InjectRepository(Message) private readonly msgRepo: Repository<Message>,
  ) {}

  async getMe(userId: string) {
    const profile = await this.profileRepo.findOne({ where: { userId } });
    const phone = await this.phoneRepo.findOne({ where: { userId, status: 'VERIFIED' } });
    if (!profile) {
      throw new ViroException('NOT_FOUND', 'Profile not found.', HttpStatus.NOT_FOUND);
    }
    return {
      userId,
      phoneE164: phone?.phoneE164 || '',
      displayName: profile.displayName,
      avatarUrl: profile.avatarUrl,
      viroId: profile.viroId,
      allowCallsFromViroId: profile.allowCallsFromViroId,
    };
  }

  async updateMe(userId: string, updates: {
    displayName?: string;
    avatarUrl?: string | null;
    viroId?: string;
    allowCallsFromViroId?: string;
  }) {
    const profile = await this.profileRepo.findOne({ where: { userId } });
    if (!profile) {
      throw new ViroException('NOT_FOUND', 'Profile not found.', HttpStatus.NOT_FOUND);
    }

    if (updates.displayName !== undefined) profile.displayName = updates.displayName;
    if (updates.avatarUrl !== undefined) profile.avatarUrl = updates.avatarUrl;
    if (updates.allowCallsFromViroId !== undefined) {
      profile.allowCallsFromViroId = updates.allowCallsFromViroId;
    }

    if (updates.viroId !== undefined) {
      const normalized = normalizeViroId(updates.viroId);
      if (!normalized) {
        throw new ViroException('VALIDATION_ERROR', 'Invalid Viro ID.', HttpStatus.BAD_REQUEST);
      }
      const existing = await this.profileRepo.findOne({ where: { viroIdNormalized: normalized } });
      if (existing && existing.userId !== userId) {
        throw new ViroException('DUPLICATE_VIRO_ID', 'Viro ID already taken.', HttpStatus.CONFLICT);
      }
      profile.viroId = formatViroId(normalized);
      profile.viroIdNormalized = normalized;
    }

    await this.profileRepo.save(profile);
    return this.getMe(userId);
  }

  /** GDPR data export for the authenticated user. */
  async exportMe(userId: string) {
    const profile = await this.getMe(userId);
    const [calls, blocks, connections, devices, memberships] = await Promise.all([
      this.callRepo.find({
        where: [{ callerUserId: userId }, { calleeUserId: userId }],
        order: { startedAt: 'DESC' },
        take: 500,
      }),
      this.blockRepo.find({ where: { blockerUserId: userId } }),
      this.connectionRepo.find({
        where: [{ requesterUserId: userId }, { recipientUserId: userId }],
      }),
      this.deviceRepo.find({ where: { userId } }),
      this.partRepo.find({ where: { userId } }),
    ]);
    const convIds = memberships.map((m) => m.conversationId);
    const messages = convIds.length
      ? await this.msgRepo.find({
          where: { conversationId: In(convIds), senderUserId: userId },
          order: { createdAt: 'DESC' },
          take: 1000,
        })
      : [];
    return {
      userId,
      exportedAt: new Date().toISOString(),
      profile,
      devices: devices.map((d) => ({
        id: d.id,
        platform: d.platform,
        createdAt: d.createdAt,
        lastSeenAt: d.lastSeenAt,
        revokedAt: d.revokedAt,
      })),
      calls,
      connections: connections.map((c) => ({
        id: c.id,
        status: c.status,
        requesterUserId: c.requesterUserId,
        recipientUserId: c.recipientUserId,
      })),
      blocks: blocks.map((b) => ({ blockedUserId: b.blockedUserId })),
      messages: messages.map((m) => ({
        id: m.id,
        conversationId: m.conversationId,
        body: m.body,
        createdAt: m.createdAt,
      })),
    };
  }

  /** Permanently close the account: revoke devices/sessions, anonymize identity. */
  async deleteMe(userId: string) {
    const user = await this.userRepo.findOne({ where: { id: userId } });
    if (!user) {
      throw new ViroException('NOT_FOUND', 'User not found.', HttpStatus.NOT_FOUND);
    }
    user.status = 'DELETED';
    await this.userRepo.save(user);

    const profile = await this.profileRepo.findOne({ where: { userId } });
    if (profile) {
      profile.displayName = 'Deleted user';
      profile.avatarUrl = null;
      profile.viroId = null;
      profile.viroIdNormalized = null;
      await this.profileRepo.save(profile);
    }

    const phones = await this.phoneRepo.find({ where: { userId } });
    for (const phone of phones) {
      phone.status = 'REVOKED';
      phone.phoneE164 = `+000${userId.replace(/-/g, '').slice(0, 15)}`.slice(0, 20);
      await this.phoneRepo.save(phone);
    }

    await this.deviceRepo
      .createQueryBuilder()
      .update()
      .set({ revokedAt: () => 'NOW()' })
      .where('user_id = :userId AND revoked_at IS NULL', { userId })
      .execute();
    await this.sessionRepo
      .createQueryBuilder()
      .update()
      .set({ revokedAt: () => 'NOW()' })
      .where('user_id = :userId AND revoked_at IS NULL', { userId })
      .execute();
    await this.pushRepo.delete({ userId });
    return { deleted: true };
  }
}
