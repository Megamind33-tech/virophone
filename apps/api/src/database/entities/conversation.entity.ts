import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
} from 'typeorm';

@Entity('conversations')
export class Conversation {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'is_group', type: 'boolean', default: false })
  isGroup!: boolean;

  @Column({ type: 'varchar', length: 120, nullable: true })
  title!: string | null;

  @Column({ type: 'varchar', length: 300, nullable: true })
  description!: string | null;

  @Column({ name: 'avatar_media_id', type: 'uuid', nullable: true })
  avatarMediaId!: string | null;

  /** The shareable link code for a group; null until an admin makes one. */
  @Column({ name: 'invite_code', type: 'varchar', length: 32, nullable: true })
  inviteCode!: string | null;

  @Column({ name: 'invite_created_at', type: 'timestamptz', nullable: true })
  inviteCreatedAt!: Date | null;

  @Column({ name: 'invite_created_by', type: 'uuid', nullable: true })
  inviteCreatedBy!: string | null;

  @Column({ name: 'dm_key', type: 'varchar', length: 73, nullable: true })
  dmKey!: string | null;

  @Column({ name: 'created_by', type: 'uuid', nullable: true })
  createdBy!: string | null;

  /** DM (the permanent 1:1 thread) or PRIVATE (deleted outright at expiresAt). */
  @Column({ type: 'varchar', length: 16, default: 'DM' })
  kind!: string;

  @Column({ name: 'expires_at', type: 'timestamptz', nullable: true })
  expiresAt!: Date | null;

  @Column({ name: 'disappearing_seconds', type: 'integer', nullable: true })
  disappearingSeconds!: number | null;

  @Column({ name: 'reset_at', type: 'timestamptz', nullable: true })
  resetAt!: Date | null;

  /**
   * Set the first time an end-to-end encrypted message arrives here, so every
   * device of everyone in the chat knows to keep sending that way. It is never
   * unset: a chat does not quietly stop being encrypted.
   */
  @Column({ type: 'boolean', default: false })
  encrypted!: boolean;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;
}
