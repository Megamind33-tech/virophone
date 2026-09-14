import { Entity, PrimaryColumn, Column, CreateDateColumn } from 'typeorm';

@Entity('blocks')
export class Block {
  @PrimaryColumn({ name: 'blocker_user_id', type: 'uuid' })
  blockerUserId!: string;

  @PrimaryColumn({ name: 'blocked_user_id', type: 'uuid' })
  blockedUserId!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}
