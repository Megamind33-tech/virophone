import { Entity, PrimaryGeneratedColumn, Column, Index, UpdateDateColumn } from 'typeorm';

@Entity('contact_preferences')
@Index(['userId', 'phoneE164'], { unique: true })
export class ContactPreference {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'user_id', type: 'uuid' })
  userId!: string;

  /** Stable across devices, unlike the handset's own contact id. */
  @Column({ name: 'phone_e164', type: 'varchar', length: 20 })
  phoneE164!: string;

  @Column({ name: 'is_favorite', type: 'boolean', default: false })
  isFavorite!: boolean;

  @Column({ name: 'custom_display_name', type: 'varchar', length: 120, nullable: true })
  customDisplayName!: string | null;

  @Column({ name: 'is_hidden', type: 'boolean', default: false })
  isHidden!: boolean;

  /**
   * Blocking a contact who is not (yet) a Viro user. The blocks table cannot
   * express this — it is keyed by blocked_user_id — so a block against a plain
   * phone number lives here and travels with the account.
   */
  @Column({ name: 'is_blocked', type: 'boolean', default: false })
  isBlocked!: boolean;

  @Column({ name: 'is_spam', type: 'boolean', default: false })
  isSpam!: boolean;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;
}
