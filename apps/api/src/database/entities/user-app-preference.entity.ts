import { Entity, PrimaryColumn, Column, UpdateDateColumn } from 'typeorm';

@Entity('user_app_preferences')
export class UserAppPreference {
  @PrimaryColumn({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ name: 'theme_mode', type: 'varchar', length: 10, default: 'SYSTEM' })
  themeMode!: string;

  @Column({ name: 'font_size', type: 'varchar', length: 10, default: 'STANDARD' })
  fontSize!: string;

  @Column({ type: 'varchar', length: 12, default: 'COMFORTABLE' })
  density!: string;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;
}
