import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  ManyToOne,
  JoinColumn,
  CreateDateColumn,
} from 'typeorm';
import { User } from './user.entity';

@Entity('devices')
export class Device {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ name: 'public_key', type: 'text' })
  publicKey!: string;

  @Column({ type: 'varchar', length: 20 })
  platform!: string;

  @Column({ name: 'app_version', type: 'varchar', length: 20 })
  appVersion!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @Column({ name: 'last_seen_at', type: 'timestamptz', default: () => 'NOW()' })
  lastSeenAt!: Date;

  @Column({ name: 'revoked_at', type: 'timestamptz', nullable: true })
  revokedAt!: Date | null;

  @Column({ name: 'integrity_status', type: 'varchar', length: 20, default: 'UNKNOWN' })
  integrityStatus!: string;

  @ManyToOne(() => User, (u) => u.devices)
  @JoinColumn({ name: 'user_id' })
  user!: User;
}
