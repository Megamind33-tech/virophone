import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  ManyToOne,
  JoinColumn,
  Index,
} from 'typeorm';
import { User } from './user.entity';

@Entity('phone_identities')
@Index(['phoneE164'], { unique: true })
export class PhoneIdentity {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ name: 'phone_e164', type: 'varchar', length: 20 })
  phoneE164!: string;

  @Column({ name: 'phone_hash', type: 'varchar', length: 64 })
  phoneHash!: string;

  @Column({ name: 'verified_at', type: 'timestamptz', nullable: true })
  verifiedAt!: Date | null;

  @Column({ type: 'varchar', length: 20, default: 'PENDING' })
  status!: string;

  @ManyToOne(() => User, (u) => u.phoneIdentities)
  @JoinColumn({ name: 'user_id' })
  user!: User;
}
