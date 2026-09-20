import { publicAvatarUrl } from '../users/avatar.util';
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Profile } from '../database/entities/profile.entity';
import { Block } from '../database/entities/block.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { EmailIdentity } from '../database/entities/email-identity.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { VisibilityService } from '../users/visibility.service';
import { normalizeViroId, formatViroId } from '../common/utils/viro-id.util';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import type { PublicProfile } from '@viro-reach/shared-types';

export interface FoundPerson {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  viroId: string | null;
  /** Their About line, when they let this viewer read it. */
  about: string | null;
  /** How the query matched. The email itself is never returned. */
  matchedBy: 'VIRO_ID' | 'EMAIL';
  /** The connection between the two, from the searcher's side. REJECTED is reported as PENDING. */
  connection: { id: string; status: string; direction: 'OUTGOING' | 'INCOMING' } | null;
  /** Whether the server would let the searcher call this person right now. */
  canCall: boolean;
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const FIND_WINDOW_MS = 10 * 60 * 1000;
const FIND_MAX_PER_WINDOW = 30;

@Injectable()
export class DirectoryService {
  /** Per-user recent lookup times: exact-match only, and throttled, so the directory can't be enumerated. */
  private readonly recentFinds = new Map<string, number[]>();

  constructor(
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(EmailIdentity) private readonly emailRepo: Repository<EmailIdentity>,
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    private readonly visibility: VisibilityService,
  ) {}

  /**
   * Exact Viro ID lookup only. No wildcard/partial search.
   */
  async exactLookup(requesterId: string, viroId: string): Promise<PublicProfile | null> {
    const normalized = normalizeViroId(viroId);
    if (!normalized) {
      throw new ViroException('VALIDATION_ERROR', 'Invalid Viro ID format.', HttpStatus.BAD_REQUEST);
    }

    const profile = await this.profileRepo.findOne({
      where: { viroIdNormalized: normalized },
    });

    if (!profile) {
      return null;
    }

    if (profile.userId === requesterId) {
      return null;
    }

    if (await this.isBlocked(requesterId, profile.userId)) {
      return null;
    }

    return {
      userId: profile.userId,
      displayName: profile.displayName,
      avatarUrl: (await this.visibility.canSee(requesterId, profile.userId, profile.photoVisibility))
        ? publicAvatarUrl(profile.avatarUrl)
        : null,
      viroId: profile.viroId || formatViroId(normalized),
    };
  }

  /**
   * Find one person by their exact Viro ID (with or without @) or their exact,
   * verified email — the way to reach someone who has no phone number on Viro.
   * Returns null when nobody matches (or they have blocked each other, or the
   * email owner turned email discovery off): the three look identical.
   */
  async find(requesterId: string, query: string): Promise<FoundPerson | null> {
    this.throttle(requesterId);
    const q = (query || '').trim();
    if (!q) throw new ViroException('VALIDATION_ERROR', 'Enter a Viro ID or an email address.', HttpStatus.BAD_REQUEST);

    let profile: Profile | null = null;
    let matchedBy: FoundPerson['matchedBy'];
    const isEmail = EMAIL_PATTERN.test(q) && !q.startsWith('@');
    if (isEmail) {
      matchedBy = 'EMAIL';
      const identity = await this.emailRepo.findOne({ where: { email: q.toLowerCase(), status: 'VERIFIED' } });
      if (identity) {
        profile = await this.profileRepo.findOne({ where: { userId: identity.userId } });
        if (profile && !profile.discoverableByEmail) profile = null;
      }
    } else {
      matchedBy = 'VIRO_ID';
      const normalized = normalizeViroId(q);
      if (!normalized) {
        throw new ViroException(
          'VALIDATION_ERROR',
          'That isn\'t a Viro ID or an email address. Viro IDs look like @chanda.m',
          HttpStatus.BAD_REQUEST,
        );
      }
      profile = await this.profileRepo.findOne({ where: { viroIdNormalized: normalized } });
    }

    if (!profile || profile.userId === requesterId) return null;
    if (await this.isBlocked(requesterId, profile.userId)) return null;

    const rows = await this.connectionRepo.find({
      where: [
        { requesterUserId: requesterId, recipientUserId: profile.userId },
        { requesterUserId: profile.userId, recipientUserId: requesterId },
      ],
    });
    const live = rows.find((r) => r.status === 'ACCEPTED')
      ?? rows.find((r) => r.status === 'PENDING')
      ?? rows.find((r) => r.status === 'REJECTED' && r.requesterUserId === requesterId);
    const connection = live
      ? {
          id: live.id,
          // Never tell someone they were declined.
          status: live.status === 'REJECTED' ? 'PENDING' : live.status,
          direction: (live.requesterUserId === requesterId ? 'OUTGOING' : 'INCOMING') as 'OUTGOING' | 'INCOMING',
        }
      : null;

    const matched = await this.matchRepo.findOne({ where: { userId: requesterId, matchedUserId: profile.userId } });
    const canCall = !!matched || live?.status === 'ACCEPTED' || profile.allowCallsFromViroId === 'EXACT_ID_ALLOWED';

    return {
      userId: profile.userId,
      displayName: profile.displayName,
      avatarUrl: (await this.visibility.canSee(requesterId, profile.userId, profile.photoVisibility))
        ? publicAvatarUrl(profile.avatarUrl)
        : null,
      about: (await this.visibility.canSee(requesterId, profile.userId, profile.aboutVisibility)) ? profile.about : null,
      viroId: profile.viroId,
      matchedBy,
      connection,
      canCall,
    };
  }

  private throttle(userId: string) {
    const now = Date.now();
    const recent = (this.recentFinds.get(userId) ?? []).filter((t) => now - t < FIND_WINDOW_MS);
    if (recent.length >= FIND_MAX_PER_WINDOW) {
      throw new ViroException('RATE_LIMITED', 'Too many searches. Try again in a few minutes.', HttpStatus.TOO_MANY_REQUESTS);
    }
    recent.push(now);
    this.recentFinds.set(userId, recent);
    if (this.recentFinds.size > 10_000) {
      for (const [k, v] of this.recentFinds) if (!v.some((t) => now - t < FIND_WINDOW_MS)) this.recentFinds.delete(k);
    }
  }

  private async isBlocked(a: string, b: string): Promise<boolean> {
    const blocked = await this.blockRepo.findOne({
      where: [
        { blockerUserId: b, blockedUserId: a },
        { blockerUserId: a, blockedUserId: b },
      ],
    });
    return !!blocked;
  }
}
