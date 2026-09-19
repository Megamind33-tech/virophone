import { Entity, PrimaryGeneratedColumn, PrimaryColumn, Column, CreateDateColumn } from 'typeorm';

@Entity('relationships')
export class Relationship {
  @PrimaryGeneratedColumn('uuid') id!: string;
  @Column({ name: 'owner_user_id', type: 'uuid' }) ownerUserId!: string;
  @Column({ name: 'subject_key', type: 'varchar', length: 80 }) subjectKey!: string;
  @Column({ name: 'subject_user_id', type: 'uuid', nullable: true }) subjectUserId!: string | null;
  @Column({ name: 'subject_phone', type: 'varchar', length: 20, nullable: true }) subjectPhone!: string | null;
  @Column({ name: 'display_name', type: 'varchar', length: 120, nullable: true }) displayName!: string | null;
  @Column({ type: 'varchar', length: 16, default: 'PERSONAL' }) category!: string;
  @Column({ name: 'relationship_type', type: 'varchar', length: 24, default: 'FRIEND' }) relationshipType!: string;
  @Column({ name: 'custom_label', type: 'varchar', length: 40, nullable: true }) customLabel!: string | null;
  @Column({ type: 'varchar', length: 16, nullable: true }) vibe!: string | null;
  @Column({ name: 'target_cadence', type: 'varchar', length: 16, nullable: true }) targetCadence!: string | null;
  @Column({ name: 'target_count', type: 'smallint', default: 1 }) targetCount!: number;
  @Column({ name: 'target_every_days', type: 'smallint', nullable: true }) targetEveryDays!: number | null;
  @Column({ name: 'target_weekday', type: 'smallint', nullable: true }) targetWeekday!: number | null;
  @Column({ name: 'target_label', type: 'varchar', length: 80, nullable: true }) targetLabel!: string | null;
  @Column({ name: 'reminders_enabled', type: 'boolean', default: true }) remindersEnabled!: boolean;
  @Column({ type: 'text', nullable: true }) notes!: string | null;
  @CreateDateColumn({ name: 'created_at' }) createdAt!: Date;
  @Column({ name: 'updated_at', type: 'timestamptz', default: () => 'NOW()' }) updatedAt!: Date;
}

@Entity('important_dates')
export class ImportantDate {
  @PrimaryGeneratedColumn('uuid') id!: string;
  @Column({ name: 'owner_user_id', type: 'uuid' }) ownerUserId!: string;
  @Column({ name: 'relationship_id', type: 'uuid' }) relationshipId!: string;
  @Column({ type: 'varchar', length: 24 }) kind!: string;
  @Column({ type: 'varchar', length: 80, nullable: true }) label!: string | null;
  @Column({ type: 'smallint' }) month!: number;
  @Column({ type: 'smallint' }) day!: number;
  @Column({ type: 'smallint', nullable: true }) year!: number | null;
  @Column({ name: 'remind_days_before', type: 'smallint', default: 1 }) remindDaysBefore!: number;
  @CreateDateColumn({ name: 'created_at' }) createdAt!: Date;
}

@Entity('commitments')
export class Commitment {
  @PrimaryGeneratedColumn('uuid') id!: string;
  @Column({ name: 'owner_user_id', type: 'uuid' }) ownerUserId!: string;
  @Column({ name: 'relationship_id', type: 'uuid', nullable: true }) relationshipId!: string | null;
  @Column({ name: 'subject_user_id', type: 'uuid', nullable: true }) subjectUserId!: string | null;
  @Column({ name: 'conversation_id', type: 'uuid', nullable: true }) conversationId!: string | null;
  @Column({ name: 'message_id', type: 'uuid', nullable: true }) messageId!: string | null;
  @Column({ type: 'varchar', length: 16, default: 'OTHER' }) kind!: string;
  @Column({ type: 'varchar', length: 280 }) text!: string;
  @Column({ name: 'due_at', type: 'timestamptz' }) dueAt!: Date;
  @Column({ type: 'varchar', length: 12, default: 'OPEN' }) status!: string;
  @Column({ name: 'completed_at', type: 'timestamptz', nullable: true }) completedAt!: Date | null;
  @CreateDateColumn({ name: 'created_at' }) createdAt!: Date;
}

@Entity('loops')
export class Loop {
  @PrimaryGeneratedColumn('uuid') id!: string;
  @Column({ name: 'conversation_id', type: 'uuid' }) conversationId!: string;
  @Column({ name: 'created_by', type: 'uuid' }) createdBy!: string;
  @Column({ type: 'varchar', length: 80 }) title!: string;
  @Column({ type: 'varchar', length: 280 }) prompt!: string;
  @Column({ type: 'varchar', length: 12 }) frequency!: string;
  @Column({ name: 'days_mask', type: 'smallint', nullable: true }) daysMask!: number | null;
  @Column({ name: 'time_of_day', type: 'varchar', length: 5, default: '19:00' }) timeOfDay!: string;
  @Column({ type: 'varchar', length: 64, default: 'Africa/Lusaka' }) timezone!: string;
  @Column({ name: 'response_kind', type: 'varchar', length: 8, default: 'ANY' }) responseKind!: string;
  @Column({ type: 'jsonb', nullable: true }) choices!: string[] | null;
  @Column({ type: 'boolean', default: true }) reciprocal!: boolean;
  @Column({ type: 'boolean', default: true }) active!: boolean;
  @CreateDateColumn({ name: 'created_at' }) createdAt!: Date;
  @Column({ name: 'updated_at', type: 'timestamptz', default: () => 'NOW()' }) updatedAt!: Date;
}

@Entity('loop_answers')
export class LoopAnswer {
  @PrimaryGeneratedColumn('uuid') id!: string;
  @Column({ name: 'loop_id', type: 'uuid' }) loopId!: string;
  @Column({ name: 'period_key', type: 'varchar', length: 16 }) periodKey!: string;
  @Column({ name: 'user_id', type: 'uuid' }) userId!: string;
  @Column({ type: 'varchar', length: 8 }) kind!: string;
  @Column({ type: 'varchar', length: 1000, nullable: true }) text!: string | null;
  @Column({ name: 'media_id', type: 'uuid', nullable: true }) mediaId!: string | null;
  @CreateDateColumn({ name: 'created_at' }) createdAt!: Date;
}

@Entity('user_achievements')
export class UserAchievement {
  @PrimaryColumn({ name: 'user_id', type: 'uuid' }) userId!: string;
  @PrimaryColumn({ name: 'achievement_key', type: 'varchar', length: 80 }) achievementKey!: string;
  @Column({ type: 'varchar', length: 80 }) title!: string;
  @Column({ type: 'varchar', length: 200 }) detail!: string;
  @CreateDateColumn({ name: 'unlocked_at' }) unlockedAt!: Date;
  @Column({ type: 'boolean', default: false }) shared!: boolean;
}

@Entity('relationship_settings')
export class RelationshipSettings {
  @PrimaryColumn({ name: 'user_id', type: 'uuid' }) userId!: string;
  @Column({ type: 'varchar', length: 64, default: 'Africa/Lusaka' }) timezone!: string;
  @Column({ name: 'quiet_start', type: 'varchar', length: 5, default: '21:30' }) quietStart!: string;
  @Column({ name: 'quiet_end', type: 'varchar', length: 5, default: '07:00' }) quietEnd!: string;
  @Column({ name: 'brief_enabled', type: 'boolean', default: true }) briefEnabled!: boolean;
  @Column({ name: 'brief_time', type: 'varchar', length: 5, default: '08:00' }) briefTime!: string;
  @Column({ type: 'varchar', length: 8, default: 'NORMAL' }) frequency!: string;
  @Column({ name: 'personal_reminders', type: 'boolean', default: true }) personalReminders!: boolean;
  @Column({ name: 'professional_reminders', type: 'boolean', default: true }) professionalReminders!: boolean;
  @Column({ name: 'date_reminders', type: 'boolean', default: true }) dateReminders!: boolean;
  @Column({ name: 'loop_notifications', type: 'boolean', default: true }) loopNotifications!: boolean;
  @Column({ name: 'achievement_notifications', type: 'boolean', default: true }) achievementNotifications!: boolean;
  @Column({ name: 'updated_at', type: 'timestamptz', default: () => 'NOW()' }) updatedAt!: Date;
}

@Entity('relationship_checkins')
export class RelationshipCheckin {
  @PrimaryGeneratedColumn('uuid') id!: string;
  @Column({ name: 'owner_user_id', type: 'uuid' }) ownerUserId!: string;
  @Column({ name: 'relationship_id', type: 'uuid' }) relationshipId!: string;
  @Column({ type: 'timestamptz', default: () => 'NOW()' }) at!: Date;
  @Column({ type: 'varchar', length: 120, nullable: true }) note!: string | null;
}
