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

  @Column({ type: 'varchar', length: 16, default: 'TEXT' })
  type!: string;

  @Column({ type: 'text', nullable: true })
  body!: string | null;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}
