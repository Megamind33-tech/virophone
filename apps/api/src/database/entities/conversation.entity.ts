import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
} from 'typeorm';

@Entity('conversations')
export class Conversation {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'is_group', type: 'boolean', default: false })
  isGroup!: boolean;

  @Column({ type: 'varchar', length: 120, nullable: true })
  title!: string | null;

  @Column({ name: 'dm_key', type: 'varchar', length: 73, nullable: true })
  dmKey!: string | null;

  @Column({ name: 'created_by', type: 'uuid', nullable: true })
  createdBy!: string | null;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;
}
