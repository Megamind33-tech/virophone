import { Entity, PrimaryColumn, Column, CreateDateColumn, Index } from 'typeorm';

/**
 * Who was named with @ in a message.
 *
 * This used to be read out of messages.metadata, which is encrypted at rest
 * now and so can't be searched. Who was mentioned is metadata the server needs
 * anyway — to notify them, and to show the @ badge — so it lives in its own
 * table rather than inside the encrypted blob.
 */
@Entity('message_mentions')
@Index(['userId', 'conversationId', 'createdAt'])
export class MessageMention {
  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ name: 'conversation_id', type: 'uuid' })
  conversationId!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}
