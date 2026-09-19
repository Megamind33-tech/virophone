import { Injectable, HttpStatus } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import * as fs from 'fs';
import { Profile } from '../database/entities/profile.entity';
import { EmailIdentity } from '../database/entities/email-identity.entity';
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
import {
  avatarFilePath,
  extensionForMime,
  publicAvatarBaseUrl,
  publicAvatarUrl,
  validateAvatarMime,
} from './avatar.util';

@Injectable()
export class UsersService {
  constructor(
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(EmailIdentity) private readonly emailRepo: Repository<EmailIdentity>,
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
    const email = await this.emailRepo.findOne({ where: { userId }, order: { createdAt: 'DESC' } });
    return {
      userId,
      phoneE164: phone?.phoneE164 || '',
      email: email?.email ?? null,
      emailVerified: email?.status === 'VERIFIED',
      displayName: profile.displayName,
      avatarUrl: publicAvatarUrl(profile.avatarUrl),
      viroId: profile.viroId,
      allowCallsFromViroId: profile.allowCallsFromViroId,
      discoverableByEmail: profile.discoverableByEmail,
      profileCompleted: profile.profileCompletedAt != null,
    };
  }

  async updateMe(userId: string, updates: {
    displayName?: string;
    avatarUrl?: string | null;
    viroId?: string;
    allowCallsFromViroId?: string;
    discoverableByEmail?: boolean;
    completeProfile?: boolean;
  }) {
    const profile = await this.profileRepo.findOne({ where: { userId } });
    if (!profile) {
      throw new ViroException('NOT_FOUND', 'Profile not found.', HttpStatus.NOT_FOUND);
    }

    if (updates.displayName !== undefined) {
      const name = updates.displayName.replace(/\s+/g, ' ').trim();
      if (!name) {
        throw new ViroException('VALIDATION_ERROR', 'Please enter your name.', HttpStatus.BAD_REQUEST);
      }
      profile.displayName = name.slice(0, 60);
    }
    if (updates.discoverableByEmail !== undefined) profile.discoverableByEmail = updates.discoverableByEmail;
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

    if (updates.completeProfile) {
      if (!profile.displayName.trim() || !profile.viroIdNormalized) {
        throw new ViroException('VALIDATION_ERROR', 'Choose your name and Viro ID first.', HttpStatus.BAD_REQUEST);
      }
      profile.profileCompletedAt = profile.profileCompletedAt ?? new Date();
    }

    await this.profileRepo.save(profile);
    return this.getMe(userId);
  }

  /**
   * Validates a Viro ID and says whether it is free for this user. Always
   * returns a few free suggestions, derived from [name] (or the requested id).
   */
  async checkViroId(userId: string, id?: string, name?: string) {
    const normalized = id ? normalizeViroId(id) : null;
    let available = false;
    if (normalized) {
      const owner = await this.profileRepo.findOne({ where: { viroIdNormalized: normalized } });
      available = !owner || owner.userId === userId;
    }
    const suggestions = await this.suggestViroIds(userId, name || id || '', normalized && available ? normalized : null);
    return {
      viroId: normalized ? formatViroId(normalized) : null,
      valid: normalized != null,
      available,
      reason: !id ? null
        : !normalized ? 'Use 3–30 letters, numbers, dots or underscores, starting with a letter or number.'
        : available ? null : 'That Viro ID is taken.',
      suggestions,
    };
  }

  private async suggestViroIds(userId: string, seed: string, exclude: string | null): Promise<string[]> {
    const base = seed
      .normalize('NFKD').replace(/[\u0300-\u036f]/g, '') // é → e
      .toLowerCase()
      .replace(/^@/, '')
      .replace(/[^a-z0-9]+/g, '.')
      .replace(/^\.+|\.+$/g, '')
      .replace(/\.{2,}/g, '.')
      .slice(0, 24) || 'viro.user';
    const parts = base.split('.').filter(Boolean);
    const raw = [
      base,
      parts.join(''),
      parts.length > 1 ? parts.join('_') : null,
      parts.length > 1 ? `${parts[0]}.${parts[parts.length - 1][0]}` : null,
    ];
    for (let i = 0; raw.length < 12 && i < 8; i++) raw.push(`${parts.join('') || 'viro'}${Math.floor(10 + Math.random() * 990)}`);
    const candidates = Array.from(new Set(raw.map((r) => (r ? normalizeViroId(r.length < 3 ? r + 'viro' : r) : null))))
      .filter((c): c is string => !!c && c !== exclude);
    if (!candidates.length) return [];
    const taken = await this.profileRepo.find({ where: { viroIdNormalized: In(candidates) } });
    const takenSet = new Set(taken.filter((p) => p.userId !== userId).map((p) => p.viroIdNormalized));
    return candidates.filter((c) => !takenSet.has(c)).slice(0, 4).map(formatViroId);
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

  async uploadAvatar(userId: string, file: Express.Multer.File) {
    if (!file?.buffer?.length) {
      throw new ViroException('VALIDATION_ERROR', 'Avatar file is required.', HttpStatus.BAD_REQUEST);
    }
    try {
      validateAvatarMime(file.mimetype);
    } catch {
      throw new ViroException(
        'VALIDATION_ERROR',
        'Avatar must be JPEG, PNG, or WebP.',
        HttpStatus.BAD_REQUEST,
      );
    }
    const ext = extensionForMime(file.mimetype);
    const targetPath = avatarFilePath(userId, ext);
    for (const suffix of ['.jpg', '.png', '.webp']) {
      const existing = avatarFilePath(userId, suffix);
      if (existing !== targetPath && fs.existsSync(existing)) {
        fs.unlinkSync(existing);
      }
    }
    fs.writeFileSync(targetPath, file.buffer);
    // The file name is the same on every upload, and the image is served with
    // a day of caching — so without a version the app keeps showing the old
    // photo after a change. A new query string is a new URL to every cache.
    const avatarUrl = `${publicAvatarBaseUrl()}/${userId}${ext}?v=${Date.now()}`;
    return this.updateMe(userId, { avatarUrl });
  }
}
