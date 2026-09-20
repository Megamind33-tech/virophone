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

  @Column({ name: 'last_delivered_at', type: 'timestamptz', nullable: true })
  lastDeliveredAt!: Date | null;

  /** Hide chat: out of the inbox until the person unhides it. */
  @Column({ type: 'boolean', default: false })
  hidden!: boolean;

  /** Delete chat (for me): messages up to here are gone for this person only. */
  @Column({ name: 'cleared_at', type: 'timestamptz', nullable: true })
  clearedAt!: Date | null;

  @Column({ name: 'muted_until', type: 'timestamptz', nullable: true })
  mutedUntil!: Date | null;

  /** Archive: out of the main inbox, into "Archived". Mine alone — the other side sees nothing. */
  @Column({ name: 'archived_at', type: 'timestamptz', nullable: true })
  archivedAt!: Date | null;

  /** Pin to the top of the inbox; newest pin first. */
  @Column({ name: 'pinned_at', type: 'timestamptz', nullable: true })
  pinnedAt!: Date | null;

  /** "Mark as unread": holds the dot until the chat is opened again. */
  @Column({ name: 'unread_marked', type: 'boolean', default: false })
  unreadMarked!: boolean;
}
