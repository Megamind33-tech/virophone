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

  /** A short line about themselves, shown on their contact page. */
  @Column({ type: "varchar", length: 139, nullable: true })
  about!: string | null;

  /** EVERYONE | CONTACTS | NOBODY — who may read [about]. */
  @Column({ name: "about_visibility", type: "varchar", length: 16, default: "EVERYONE" })
  aboutVisibility!: string;

  /** EVERYONE | CONTACTS | NOBODY — who may see their photo. */
  @Column({ name: "photo_visibility", type: "varchar", length: 16, default: "EVERYONE" })
  photoVisibility!: string;

  /** EVERYONE | CONTACTS | NOBODY — who may see when they were last here. */
  @Column({ name: "last_seen_visibility", type: "varchar", length: 16, default: "CONTACTS" })
  lastSeenVisibility!: string;

  @Column({ name: "last_seen_at", type: "timestamptz", nullable: true })
  lastSeenAt!: Date | null;

  /** Set once the person has chosen their name and Viro ID; NULL shows the setup step. */
  @Column({ name: 'profile_completed_at', type: 'timestamptz', nullable: true })
  profileCompletedAt!: Date | null;

  /**
   * When they were born, as "YYYY-MM-DD". Held, never shown: at most the day
   * and month are shared, and only as [birthdayVisibility] allows.
   */
  @Column({ name: 'birth_date', type: 'date', nullable: true })
  birthDate!: string | null;

  /** EVERYONE | CONTACTS | NOBODY — who may see the birthday (never the year). */
  @Column({ name: 'birthday_visibility', type: 'varchar', length: 16, default: 'NOBODY' })
  birthdayVisibility!: string;

  /** Whether an exact, verified email match can find this person in Find people. */
  @Column({ name: 'discoverable_by_email', type: 'boolean', default: true })
  discoverableByEmail!: boolean;

  @OneToOne(() => User, (u) => u.profile)
  @JoinColumn({ name: 'user_id' })
  user!: User;
}
