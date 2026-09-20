import { encryptedJsonColumn, encryptedTextColumn } from '../../common/crypto/field-cipher';
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn } from 'typeorm';

@Entity('messages')
export class Message {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'conversation_id', type: 'uuid' })
  conversationId!: string;

  @Column({ name: 'sender_user_id', type: 'uuid' })
  senderUserId!: string;

  @Column({ name: 'sender_device_id', type: 'uuid', nullable: true })
  senderDeviceId!: string | null;

  @Column({ name: 'client_msg_id', type: 'varchar', length: 64, nullable: true })
  clientMsgId!: string | null;

  /** TEXT | VOICE | SYSTEM | LOOP */
  @Column({ type: 'varchar', length: 16, default: 'TEXT' })
  type!: string;

  /** Encrypted at rest; the transformer hides and reveals it. */
  @Column({ type: 'text', nullable: true, transformer: encryptedTextColumn })
  body!: string | null;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  /** Bumped on every change; devices sync on it. Set explicitly, never by the ORM. */
  @Column({ name: 'updated_at', type: 'timestamptz', default: () => 'NOW()' })
  updatedAt!: Date;

  @Column({ name: 'reply_to_id', type: 'uuid', nullable: true })
  replyToId!: string | null;

  @Column({ name: 'edited_at', type: 'timestamptz', nullable: true })
  editedAt!: Date | null;

  @Column({ name: 'deleted_at', type: 'timestamptz', nullable: true })
  deletedAt!: Date | null;

  @Column({ name: 'expires_at', type: 'timestamptz', nullable: true })
  expiresAt!: Date | null;

  @Column({ name: 'view_once', type: 'boolean', default: false })
  viewOnce!: boolean;

  @Column({ name: 'media_id', type: 'uuid', nullable: true })
  mediaId!: string | null;

  @Column({ type: 'boolean', default: false })
  forwarded!: boolean;

  @Column({ name: 'deliver_at', type: 'timestamptz', nullable: true })
  deliverAt!: Date | null;

  /**
   * When a live location share ends. For sealed messages this is the only
   * thing the server knows about it: when, never where.
   */
  @Column({ name: 'live_until', type: 'timestamptz', nullable: true })
  liveUntil!: Date | null;

  /**
   * Encrypted at rest, so it can no longer be queried inside: anything the
   * server searches on lives in its own table (see message_mentions).
   */
  @Column({ type: 'jsonb', nullable: true, transformer: encryptedJsonColumn })
  metadata!: Record<string, unknown> | null;
}
