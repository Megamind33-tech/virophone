import { Entity, PrimaryColumn, Column, CreateDateColumn, Index } from 'typeorm';

/**
 * One attempt to link another device. The browser holds the secret; the phone
 * approves by typing the code. Tokens live here only between approval and the
 * browser collecting them.
 */
@Entity('device_link_requests')
export class DeviceLinkRequest {
  @PrimaryColumn('uuid')
  id!: string;

  /** Shown in the browser, typed on the phone. */
  @Index({ unique: true })
  @Column({ type: 'varchar', length: 16 })
  code!: string;

  /** Proves the browser collecting the tokens is the one that asked. */
  @Column({ name: 'secret_hash', type: 'varchar', length: 64 })
  secretHash!: string;

  @Column({ name: 'user_id', type: 'uuid', nullable: true })
  userId!: string | null;

  @Column({ name: 'device_id', type: 'uuid', nullable: true })
  deviceId!: string | null;

  @Column({ type: 'varchar', length: 20, default: 'WEB' })
  platform!: string;

  /** What the browser calls itself, for the Devices list. */
  @Column({ type: 'varchar', length: 80, nullable: true })
  label!: string | null;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @Column({ name: 'expires_at', type: 'timestamptz' })
  expiresAt!: Date;

  @Column({ name: 'approved_at', type: 'timestamptz', nullable: true })
  approvedAt!: Date | null;

  @Column({ name: 'claimed_at', type: 'timestamptz', nullable: true })
  claimedAt!: Date | null;

  @Column({ type: 'jsonb', nullable: true })
  tokens!: { accessToken: string; refreshToken: string; expiresIn: number } | null;
}
