import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  Index,
} from 'typeorm';

@Entity('email_identities')
@Index(['email'], { unique: true })
export class EmailIdentity {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ type: 'varchar', length: 255 })
  email!: string;

  @Column({ name: 'verified_at', type: 'timestamptz', nullable: true })
  verifiedAt!: Date | null;

  @Column({ type: 'varchar', length: 20, default: 'PENDING' })
  status!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}
