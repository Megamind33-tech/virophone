import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn } from 'typeorm';

@Entity('calls')
export class Call {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'caller_user_id', type: 'uuid' })
  callerUserId!: string;

  @Column({ name: 'callee_user_id', type: 'uuid' })
  calleeUserId!: string;

  @CreateDateColumn({ name: 'started_at' })
  startedAt!: Date;

  @Column({ name: 'answered_at', type: 'timestamptz', nullable: true })
  answeredAt!: Date | null;

  @Column({ name: 'ended_at', type: 'timestamptz', nullable: true })
  endedAt!: Date | null;

  @Column({ type: 'varchar', length: 20, default: 'INITIATED' })
  status!: string;

  @Column({ name: 'route_type', type: 'varchar', length: 20, nullable: true })
  routeType!: string | null;
}
