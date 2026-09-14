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
import { normalizeE164 } from '../common/utils/phone.util';
import { hashPhoneForStorage } from '../common/utils/hash.util';
import type { ContactDiscoveryMatch } from '@viro-reach/shared-types';

@Injectable()
export class ContactsService {
  private readonly maxBatch = parseInt(process.env.CONTACT_DISCOVERY_MAX_BATCH || '200', 10);
  private readonly hashSalt = process.env.CONTACT_HASH_SALT || 'dev_contact_salt';

  constructor(
    @InjectRepository(PhoneIdentity) private readonly phoneRepo: Repository<PhoneIdentity>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    private readonly securityService: SecurityService,
  ) {}

  /**
   * Authenticated contact discovery.
   * Client sends normalized E.164 numbers over TLS; server hashes with server-only salt.
   * This is enumeration-resistant for UNAUTHENTICATED callers only — NOT PSI.
   */
  async discover(
    userId: string,
    phonesE164: string[],
    defaultRegion = 'ZM',
  ): Promise<{ matches: ContactDiscoveryMatch[] }> {
    if (phonesE164.length > this.maxBatch) {
      throw new ViroException(
        'VALIDATION_ERROR',
        `Maximum ${this.maxBatch} contacts per batch.`,
        HttpStatus.BAD_REQUEST,
      );
    }

    if (phonesE164.length === 0) {
      return { matches: [] };
    }

    if (phonesE164.length > this.maxBatch * 0.9) {
      await this.securityService.logEvent({
        userId,
        eventType: 'SUSPICIOUS_ENUMERATION',
        severity: 'MEDIUM',
        metadata: { batchSize: phonesE164.length },
      });
    }

    const normalizedPhones: string[] = [];
    for (const raw of phonesE164) {
      const e164 = normalizeE164(raw, defaultRegion as 'ZM');
      if (!e164) {
        throw new ViroException('INVALID_E164', `Invalid phone number: ${raw}`, HttpStatus.BAD_REQUEST);
      }
      normalizedPhones.push(e164);
    }

    const phoneHashes = normalizedPhones.map((p) => hashPhoneForStorage(p, this.hashSalt));

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
    const phoneToHash = new Map(normalizedPhones.map((p, i) => [p, phoneHashes[i]]));
    const matches: ContactDiscoveryMatch[] = [];

    for (const phoneE164 of normalizedPhones) {
      const hash = phoneToHash.get(phoneE164)!;
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
        phoneE164,
        userId: identity.userId,
        viroId: profile.viroId || '',
        displayName: profile.displayName,
        avatarUrl: profile.avatarUrl,
        relationshipState,
      });

      const expiresAt = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000);
      await this.matchRepo.upsert(
        { userId, matchedUserId: identity.userId, phoneHash: hash, expiresAt },
        ['userId', 'matchedUserId'],
      );
    }

    return { matches };
  }
}
