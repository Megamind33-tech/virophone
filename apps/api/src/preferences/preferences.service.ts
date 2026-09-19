import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { ContactPreference } from '../database/entities/contact-preference.entity';
import { UserAppPreference } from '../database/entities/user-app-preference.entity';
import { normalizeE164 } from '../common/utils/phone.util';

export interface ContactPreferenceInput {
  phoneE164: string;
  isFavorite?: boolean;
  customDisplayName?: string | null;
  isHidden?: boolean;
  isBlocked?: boolean;
  isSpam?: boolean;
}

export interface AppPreferenceInput {
  themeMode?: string;
  fontSize?: string;
  density?: string;
}

const THEMES = new Set(['SYSTEM', 'LIGHT', 'DARK']);
const FONT_SIZES = new Set(['SMALL', 'STANDARD', 'LARGE']);
const DENSITIES = new Set(['COMFORTABLE', 'COMPACT']);

/**
 * Preferences that belong to the account rather than the handset, so a new or
 * replacement phone restores them on sign-in. Everything else a user cares
 * about — calls, messages, contacts, connections — was already server-side;
 * these were the stragglers that only existed on the device.
 */
@Injectable()
export class PreferencesService {
  constructor(
    @InjectRepository(ContactPreference)
    private readonly contactRepo: Repository<ContactPreference>,
    @InjectRepository(UserAppPreference)
    private readonly appRepo: Repository<UserAppPreference>,
  ) {}

  async getAll(userId: string) {
    const [contacts, app] = await Promise.all([
      this.contactRepo.find({ where: { userId } }),
      this.appRepo.findOne({ where: { userId } }),
    ]);
    return {
      contacts: contacts.map((c) => ({
        phoneE164: c.phoneE164,
        isFavorite: c.isFavorite,
        customDisplayName: c.customDisplayName,
        isHidden: c.isHidden,
        isBlocked: c.isBlocked,
        isSpam: c.isSpam,
      })),
      appearance: {
        themeMode: app?.themeMode ?? 'SYSTEM',
        fontSize: app?.fontSize ?? 'STANDARD',
        density: app?.density ?? 'COMFORTABLE',
      },
    };
  }

  /**
   * Upserts a batch. The client sends whole rows rather than deltas so a sync
   * that was interrupted halfway cannot leave a preference half-applied.
   */
  async upsertContacts(userId: string, items: ContactPreferenceInput[]) {
    let written = 0;
    for (const item of items) {
      const phone = normalizeE164(item.phoneE164);
      if (!phone) continue;
      const existing = await this.contactRepo.findOne({
        where: { userId, phoneE164: phone },
      });
      const row =
        existing ??
        this.contactRepo.create({ userId, phoneE164: phone });
      if (item.isFavorite !== undefined) row.isFavorite = item.isFavorite;
      if (item.isHidden !== undefined) row.isHidden = item.isHidden;
      if (item.isBlocked !== undefined) row.isBlocked = item.isBlocked;
      if (item.isSpam !== undefined) row.isSpam = item.isSpam;
      if (item.customDisplayName !== undefined) {
        const trimmed = (item.customDisplayName ?? '').trim();
        row.customDisplayName = trimmed.length ? trimmed.slice(0, 120) : null;
      }
      // A row that carries no preference at all is noise — drop it rather than
      // accumulating one empty row per contact the user ever scrolled past.
      if (
        !row.isFavorite &&
        !row.isHidden &&
        !row.isBlocked &&
        !row.isSpam &&
        !row.customDisplayName
      ) {
        if (existing) await this.contactRepo.delete({ id: existing.id });
        continue;
      }
      await this.contactRepo.save(row);
      written += 1;
    }
    return { written };
  }

  async upsertAppearance(userId: string, input: AppPreferenceInput) {
    const existing = await this.appRepo.findOne({ where: { userId } });
    const row = existing ?? this.appRepo.create({ userId });
    // Validated against known values: these come straight back to the client
    // as enum names, and an unknown one would crash the app's valueOf().
    if (input.themeMode && THEMES.has(input.themeMode)) row.themeMode = input.themeMode;
    if (input.fontSize && FONT_SIZES.has(input.fontSize)) row.fontSize = input.fontSize;
    if (input.density && DENSITIES.has(input.density)) row.density = input.density;
    await this.appRepo.save(row);
    return this.getAll(userId).then((r) => r.appearance);
  }
}
