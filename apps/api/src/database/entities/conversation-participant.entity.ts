import { Entity, PrimaryColumn, Column, CreateDateColumn } from 'typeorm';

@Entity('conversation_participants')
export class ConversationParticipant {
  @PrimaryColumn({ name: 'conversation_id', type: 'uuid' })
  conversationId!: string;

  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ type: 'varchar', length: 10, default: 'MEMBER' })
  role!: string;

  @CreateDateColumn({ name: 'joined_at' })
  joinedAt!: Date;

  @Column({ name: 'last_read_at', type: 'timestamptz', nullable: true })
  lastReadAt!: Date | null;
}
