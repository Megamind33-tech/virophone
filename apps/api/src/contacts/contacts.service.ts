import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, In } from 'typeorm';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Profile } from '../database/entities/profile.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { Block } from '../database/entities/block.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { SecurityService } from '../security/security.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import type { ContactDiscoveryMatch } from '@viro-reach/shared-types';

@Injectable()
export class ContactsService {
  private readonly maxBatch = parseInt(process.env.CONTACT_DISCOVERY_MAX_BATCH || '200', 10);

  constructor(
    @InjectRepository(PhoneIdentity) private readonly phoneRepo: Repository<PhoneIdentity>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    private readonly securityService: SecurityService,
  ) {}

  async discover(userId: string, phoneHashes: string[]): Promise<{ matches: ContactDiscoveryMatch[] }> {
    if (phoneHashes.length > this.maxBatch) {
      throw new ViroException(
        'VALIDATION_ERROR',
        `Maximum ${this.maxBatch} contacts per batch.`,
        HttpStatus.BAD_REQUEST,
      );
    }

    if (phoneHashes.length === 0) {
      return { matches: [] };
    }

    // Audit suspicious enumeration
    if (phoneHashes.length > this.maxBatch * 0.9) {
      await this.securityService.logEvent({
        userId,
        eventType: 'SUSPICIOUS_ENUMERATION',
        severity: 'MEDIUM',
        metadata: { batchSize: phoneHashes.length },
      });
    }

    const identities = await this.phoneRepo.find({
      where: { phoneHash: In(phoneHashes), status: 'VERIFIED' },
    });

    if (identities.length === 0) {
      return { matches: [] };
    }

    const blockedByMe = await this.blockRepo.find({ where: { blockerUserId: userId } });
    const blockedMe = await this.blockRepo.find({ where: { blockedUserId: userId } });
    const blockedIds = new Set([
      ...blockedByMe.map((b) => b.blockedUserId),
      ...blockedMe.map((b) => b.blockerUserId),
    ]);

    const connections = await this.connectionRepo.find({
      where: [
        { requesterUserId: userId, status: 'ACCEPTED' },
        { recipientUserId: userId, status: 'ACCEPTED' },
      ],
    });
    const connectedIds = new Set(
      connections.map((c) =>
        c.requesterUserId === userId ? c.recipientUserId : c.requesterUserId,
      ),
    );

    const hashToIdentity = new Map(identities.map((i) => [i.phoneHash, i]));
    const matches: ContactDiscoveryMatch[] = [];

    for (const hash of phoneHashes) {
      const identity = hashToIdentity.get(hash);
      if (!identity || identity.userId === userId) continue;
      if (blockedIds.has(identity.userId)) continue;

      const profile = await this.profileRepo.findOne({ where: { userId: identity.userId } });
      if (!profile) continue;

      const isConnection = connectedIds.has(identity.userId);
      const relationshipState = isConnection
        ? 'PHONE_CONTACT_AND_CONNECTION'
        : 'PHONE_CONTACT';

      matches.push({
        phoneHash: hash,
        userId: identity.userId,
        viroId: profile.viroId || '',
        displayName: profile.displayName,
        avatarUrl: profile.avatarUrl,
        relationshipState,
      });

      // Store match metadata (not address book copy)
      const expiresAt = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000);
      await this.matchRepo.upsert(
        { userId, matchedUserId: identity.userId, phoneHash: hash, expiresAt },
        ['userId', 'matchedUserId'],
      );
    }

    return { matches };
  }
}
