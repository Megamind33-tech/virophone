import { Column, CreateDateColumn, Entity, Index, PrimaryColumn, PrimaryGeneratedColumn, UpdateDateColumn } from 'typeorm';

/**
 * What one device publishes so others can start an encrypted session with it.
 * Public keys only — the private halves never leave the device.
 */
@Entity('device_identity_keys')
export class DeviceIdentityKey {
  @PrimaryColumn({ name: 'device_id', type: 'uuid' })
  deviceId!: string;

  @Index()
  @Column({ name: 'user_id', type: 'uuid' })
  userId!: string;

  /** The device's registration id, as the protocol uses it. */
  @Column({ name: 'registration_id', type: 'integer' })
  registrationId!: number;

  @Column({ name: 'identity_key', type: 'text' })
  identityKey!: string;

  @Column({ name: 'signed_prekey_id', type: 'integer' })
  signedPrekeyId!: number;

  @Column({ name: 'signed_prekey', type: 'text' })
  signedPrekey!: string;

  /** Signed by the identity key, so a swapped prekey is detectable. */
  @Column({ name: 'signed_prekey_signature', type: 'text' })
  signedPrekeySignature!: string;

  /**
   * The Kyber half of the handshake. A session cannot be started without it,
   * and like the signed prekey it is signed by the identity key.
   */
  @Column({ name: 'kyber_prekey_id', type: 'integer', nullable: true })
  kyberPrekeyId!: number | null;

  @Column({ name: 'kyber_prekey', type: 'text', nullable: true })
  kyberPrekey!: string | null;

  @Column({ name: 'kyber_prekey_signature', type: 'text', nullable: true })
  kyberPrekeySignature!: string | null;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt!: Date;
}

/** Used once and never again — what makes the first message to an offline device safe. */
@Entity('device_one_time_prekeys')
export class DeviceOneTimePrekey {
  @PrimaryGeneratedColumn({ type: 'bigint' })
  id!: string;

  @Column({ name: 'device_id', type: 'uuid' })
  deviceId!: string;

  @Column({ name: 'key_id', type: 'integer' })
  keyId!: number;

  @Column({ name: 'public_key', type: 'text' })
  publicKey!: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;

  @Column({ name: 'consumed_at', type: 'timestamptz', nullable: true })
  consumedAt!: Date | null;
}

/**
 * One encrypted message as one recipient device receives it. The server stores
 * and forwards this without being able to open it.
 */
@Entity('message_envelopes')
export class MessageEnvelope {
  @PrimaryColumn({ name: 'message_id', type: 'uuid' })
  messageId!: string;

  @PrimaryColumn({ name: 'device_id', type: 'uuid' })
  deviceId!: string;

  @Column({ name: 'user_id', type: 'uuid' })
  userId!: string;

  @Column({ type: 'text' })
  ciphertext!: string;

  /** 1 = a message in an established session, 3 = the one that starts it. */
  @Column({ name: 'envelope_type', type: 'smallint', default: 1 })
  envelopeType!: number;

  @CreateDateColumn({ name: 'created_at' })
  createdAt!: Date;
}
