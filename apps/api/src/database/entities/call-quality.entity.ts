import { Entity, PrimaryColumn, Column } from 'typeorm';

@Entity('call_quality')
export class CallQuality {
  @PrimaryColumn({ name: 'call_id', type: 'uuid' })
  callId!: string;

  @Column({ type: 'float', nullable: true })
  latency!: number | null;

  @Column({ type: 'float', nullable: true })
  jitter!: number | null;

  @Column({ name: 'packet_loss', type: 'float', nullable: true })
  packetLoss!: number | null;

  @Column({ type: 'float', nullable: true })
  bitrate!: number | null;

  @Column({ type: 'varchar', length: 20, nullable: true })
  codec!: string | null;

  @Column({ type: 'varchar', length: 20, nullable: true })
  route!: string | null;

  @Column({ type: 'boolean', default: false })
  relayed!: boolean;
}
