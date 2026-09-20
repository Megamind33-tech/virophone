import { Entity, PrimaryColumn, PrimaryGeneratedColumn, Column, CreateDateColumn } from 'typeorm';

@Entity('message_reactions')
export class MessageReaction {
  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ type: 'varchar', length: 32 })
  emoji!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}

@Entity('message_hidden')
export class MessageHidden {
  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}

@Entity('message_views')
export class MessageView {
  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @CreateDateColumn({ name: 'viewed_at' })
  viewedAt!: Date;
}

@Entity('message_stars')
export class MessageStar {
  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}

@Entity('conversation_pins')
export class ConversationPin {
  @PrimaryColumn({ name: 'conversation_id', type: 'uuid' })
  conversationId!: string;

  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @Column({ name: 'pinned_by', type: 'uuid', nullable: true })
  pinnedBy!: string | null;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}

@Entity('poll_votes')
export class PollVote {
  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @PrimaryColumn({ name: 'option_index', type: 'smallint' })
  optionIndex!: number;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}

@Entity('media_objects')
export class MediaObject {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'owner_user_id', type: 'uuid' })
  ownerUserId!: string;

  @Column({ type: 'varchar', length: 16 })
  kind!: string;

  @Column({ type: 'varchar', length: 64 })
  mime!: string;

  @Column({ name: 'size_bytes', type: 'integer' })
  sizeBytes!: number;

  @Column({ name: 'duration_ms', type: 'integer', nullable: true })
  durationMs!: number | null;

  @Column({ type: 'text', nullable: true })
  waveform!: string | null;

  @Column({ name: 'file_name', type: 'varchar', length: 80 })
  fileName!: string;

  /** For documents: the name the sender saw, shown to the recipient. */
  @Column({ name: 'original_name', type: 'varchar', length: 255, nullable: true })
  originalName!: string | null;

  @Column({ type: 'integer', nullable: true })
  width!: number | null;

  @Column({ type: 'text', nullable: true })
  transcript!: string | null;

  @Column({ name: 'transcript_lang', type: 'varchar', length: 12, nullable: true })
  transcriptLang!: string | null;

  @Column({ name: 'transcribed_at', type: 'timestamptz', nullable: true })
  transcribedAt!: Date | null;

  @Column({ type: 'integer', nullable: true })
  height!: number | null;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}
