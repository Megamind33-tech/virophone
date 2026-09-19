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

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;
}
