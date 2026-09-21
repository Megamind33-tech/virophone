import { Column, CreateDateColumn, Entity, PrimaryColumn, UpdateDateColumn } from 'typeorm';

/**
 * One person's encrypted backup of their own chats.
 *
 * The server holds the bytes and can say how big they are and when they
 * arrived. It cannot open them: the key is the recovery key on the person's
 * phone, and it never leaves it.
 */
@Entity('message_backups')
export class MessageBackup {
  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ name: 'file_name', type: 'varchar', length: 80 })
  fileName!: string;

  @Column({ name: 'size_bytes', type: 'bigint' })
  sizeBytes!: string;

  @Column({ name: 'message_count', type: 'integer', default: 0 })
  messageCount!: number;

  @Column({ name: 'conversation_count', type: 'integer', default: 0 })
  conversationCount!: number;

  @Column({ name: 'device_id', type: 'uuid', nullable: true })
  deviceId!: string | null;

  @Column({ type: 'integer', default: 1 })
  version!: number;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;
}
