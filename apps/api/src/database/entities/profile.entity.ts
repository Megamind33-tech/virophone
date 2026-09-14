import { Entity, PrimaryColumn, Column, OneToOne, JoinColumn, Index } from 'typeorm';
import { User } from './user.entity';

@Entity('profiles')
export class Profile {
  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ name: 'display_name', type: 'varchar', length: 100, default: '' })
  displayName!: string;

  @Column({ name: 'avatar_url', type: 'varchar', length: 500, nullable: true })
  avatarUrl!: string | null;

  @Column({ name: 'viro_id', type: 'varchar', length: 32, nullable: true })
  viroId!: string | null;

  @Index({ unique: true })
  @Column({ name: 'viro_id_normalized', type: 'varchar', length: 32, nullable: true })
  viroIdNormalized!: string | null;

  @Column({
    name: 'allow_calls_from_viro_id',
    type: 'varchar',
    length: 30,
    default: 'CONNECTIONS_ONLY',
  })
  allowCallsFromViroId!: string;

  @OneToOne(() => User, (u) => u.profile)
  @JoinColumn({ name: 'user_id' })
  user!: User;
}
