import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  Index,
} from 'typeorm';

@Entity('viro_connections')
@Index(['requesterUserId', 'recipientUserId'], { unique: true })
export class ViroConnection {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'requester_user_id', type: 'uuid' })
  requesterUserId!: string;

  @Column({ name: 'recipient_user_id', type: 'uuid' })
  recipientUserId!: string;

  @Column({ type: 'varchar', length: 20, default: 'PENDING' })
  status!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @Column({ name: 'accepted_at', type: 'timestamptz', nullable: true })
  acceptedAt!: Date | null;
}
