import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';

export type Visibility = 'EVERYONE' | 'CONTACTS' | 'NOBODY';
export const VISIBILITY_CHOICES: Visibility[] = ['EVERYONE', 'CONTACTS', 'NOBODY'];

/**
 * Who may see someone's photo, About line and last seen.
 *
 * "Contacts" means the people who can already reach them: someone holding
 * their number (a contact match) or an accepted connection. Blocking is
 * handled where each field is read — a blocked viewer never gets that far.
 */
@Injectable()
export class VisibilityService {
  constructor(
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
  ) {}

  /** True when [viewerId] may see a field the owner set to [setting]. */
  async canSee(viewerId: string, ownerId: string, setting: string | null | undefined): Promise<boolean> {
    if (viewerId === ownerId) return true;
    const value = (setting || 'EVERYONE').toUpperCase();
    if (value === 'NOBODY') return false;
    if (value !== 'CONTACTS') return true;
    return this.isContact(viewerId, ownerId);
  }

  /** The same question for many people at once, for list endpoints. */
  async filterVisible(viewerId: string, owners: { userId: string; setting: string | null | undefined }[]): Promise<Set<string>> {
    const out = new Set<string>();
    const needContact: string[] = [];
    for (const o of owners) {
      if (o.userId === viewerId) {
        out.add(o.userId);
        continue;
      }
      const value = (o.setting || 'EVERYONE').toUpperCase();
      if (value === 'EVERYONE') out.add(o.userId);
      else if (value === 'CONTACTS') needContact.push(o.userId);
    }
    if (needContact.length) {
      for (const id of await this.contactsAmong(viewerId, needContact)) out.add(id);
    }
    return out;
  }

  /** Does the viewer hold this person's number, or are they connected? */
  async isContact(viewerId: string, ownerId: string): Promise<boolean> {
    const match = await this.matchRepo.findOne({ where: { userId: viewerId, matchedUserId: ownerId } });
    if (match) return true;
    const connection = await this.connectionRepo.findOne({
      where: [
        { requesterUserId: viewerId, recipientUserId: ownerId, status: 'ACCEPTED' },
        { requesterUserId: ownerId, recipientUserId: viewerId, status: 'ACCEPTED' },
      ],
    });
    return !!connection;
  }

  private async contactsAmong(viewerId: string, ownerIds: string[]): Promise<Set<string>> {
    const out = new Set<string>();
    const matches = await this.matchRepo.find({ where: { userId: viewerId, matchedUserId: In(ownerIds) } });
    matches.forEach((m) => out.add(m.matchedUserId));
    const connections = await this.connectionRepo.find({
      where: [
        { requesterUserId: viewerId, recipientUserId: In(ownerIds), status: 'ACCEPTED' },
        { requesterUserId: In(ownerIds), recipientUserId: viewerId, status: 'ACCEPTED' },
      ],
    });
    connections.forEach((c) => out.add(c.requesterUserId === viewerId ? c.recipientUserId : c.requesterUserId));
    return out;
  }

  /**
   * Last seen is reciprocal: someone who hides their own doesn't get to read
   * anyone else's. Without that, hiding is a one-way mirror.
   */
  async canSeeLastSeen(viewerId: string, owner: Profile): Promise<boolean> {
    if (viewerId === owner.userId) return true;
    const mine = await this.profileRepo.findOne({ where: { userId: viewerId } });
    if ((mine?.lastSeenVisibility || 'CONTACTS').toUpperCase() === 'NOBODY') return false;
    return this.canSee(viewerId, owner.userId, owner.lastSeenVisibility);
  }
}
