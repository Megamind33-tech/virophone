import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  Index,
} from 'typeorm';

/**
 * Stores only match metadata — NOT a copy of the user's address book.
 * Retention: matches expire after 90 days and are re-derived on next discovery.
 */
@Entity('contact_matches')
@Index(['userId', 'matchedUserId'], { unique: true })
export class ContactMatch {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ name: 'matched_user_id', type: 'uuid' })
  matchedUserId!: string;

  @Column({ name: 'phone_hash', type: 'varchar', length: 64 })
  phoneHash!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @Column({ name: 'expires_at', type: 'timestamptz' })
  expiresAt!: Date;
}
