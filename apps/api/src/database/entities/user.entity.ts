import {
  Entity,
  PrimaryColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
  OneToMany,
  OneToOne,
} from 'typeorm';
import { PhoneIdentity } from './phone-identity.entity';
import { Profile } from './profile.entity';
import { Device } from './device.entity';
import { Session } from './session.entity';

@Entity('users')
export class User {
  @PrimaryColumn('uuid')
  id!: string;

  @Column({ type: 'varchar', length: 20, default: 'ACTIVE' })
  status!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;

  @OneToMany(() => PhoneIdentity, (pi) => pi.user)
  phoneIdentities!: PhoneIdentity[];

  @OneToOne(() => Profile, (p) => p.user)
  profile!: Profile;

  @OneToMany(() => Device, (d) => d.user)
  devices!: Device[];

  @OneToMany(() => Session, (s) => s.user)
  sessions!: Session[];
}
