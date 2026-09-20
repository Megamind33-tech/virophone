import { HttpStatus, Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, IsNull, Repository } from 'typeorm';
import { Device } from '../database/entities/device.entity';
import { DeviceIdentityKey, DeviceOneTimePrekey } from '../database/entities/e2ee.entity';
import { BlocksService } from '../blocks/blocks.service';
import { ViroException } from '../common/exceptions/viro.exception';

/** A phone tops up when it drops below this; the server never lets it exceed the cap. */
export const PREKEY_LOW_WATER = 20;
export const PREKEY_MAX_STORED = 300;
/** Upload size cap, so one request cannot fill the table. */
const PREKEY_BATCH_MAX = 150;
/** Consumed prekeys are kept this long, then swept. */
export const PREKEY_RETENTION_DAYS = 30;

export interface PrekeyInput {
  keyId: number;
  publicKey: string;
}

export interface KeyBundleInput {
  registrationId: number;
  identityKey: string;
  signedPreKey: { keyId: number; publicKey: string; signature: string };
  oneTimePreKeys?: PrekeyInput[];
}

export interface DeviceBundle {
  deviceId: string;
  registrationId: number;
  identityKey: string;
  signedPreKey: { keyId: number; publicKey: string; signature: string };
  /** Absent when this device has run out: the session still starts, with weaker forward secrecy. */
  preKey: { keyId: number; publicKey: string } | null;
}

/**
 * The key directory.
 *
 * Devices publish public keys here and collect each other's. Private keys
 * never arrive — nothing in this file could decrypt a message even if it
 * wanted to. The server's only real job is to hand out each one-time prekey
 * exactly once.
 */
@Injectable()
export class KeysService {
  constructor(
    @InjectRepository(DeviceIdentityKey) private readonly identityRepo: Repository<DeviceIdentityKey>,
    @InjectRepository(DeviceOneTimePrekey) private readonly prekeyRepo: Repository<DeviceOneTimePrekey>,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    private readonly blocks: BlocksService,
  ) {}

  private fail(code: 'VALIDATION_ERROR' | 'FORBIDDEN' | 'NOT_FOUND', message: string, status: HttpStatus): never {
    throw new ViroException(code, message, status);
  }

  /** Key material is base64 and small; anything else is a bug or an attack. */
  private key(value: unknown, field: string, max = 512): string {
    const s = typeof value === 'string' ? value.trim() : '';
    if (!s || s.length > max || !/^[A-Za-z0-9+/=_-]+$/.test(s)) {
      this.fail('VALIDATION_ERROR', `Invalid ${field}.`, HttpStatus.BAD_REQUEST);
    }
    return s;
  }

  private keyId(value: unknown, field: string): number {
    const n = typeof value === 'number' ? value : Number.NaN;
    if (!Number.isInteger(n) || n < 0 || n > 0xffffff) {
      this.fail('VALIDATION_ERROR', `Invalid ${field}.`, HttpStatus.BAD_REQUEST);
    }
    return n;
  }

  /** Publishes (or replaces) this device's identity and signed prekey. */
  async publish(userId: string, deviceId: string, input: KeyBundleInput) {
    if (!deviceId) {
      this.fail('VALIDATION_ERROR', 'This session has no device.', HttpStatus.BAD_REQUEST);
    }
    const registrationId = this.keyId(input?.registrationId, 'registration id');
    const identityKey = this.key(input?.identityKey, 'identity key');
    const signedPrekeyId = this.keyId(input?.signedPreKey?.keyId, 'signed prekey id');
    const signedPrekey = this.key(input?.signedPreKey?.publicKey, 'signed prekey');
    const signature = this.key(input?.signedPreKey?.signature, 'signed prekey signature');

    const existing = await this.identityRepo.findOne({ where: { deviceId } });
    // The identity key is what the other side's safety number is built from.
    // Changing it is legitimate on a reinstall, but never silent: the change
    // is what the warning on the other phone is for.
    await this.identityRepo.save(
      this.identityRepo.create({
        ...(existing ?? {}),
        deviceId,
        userId,
        registrationId,
        identityKey,
        signedPrekeyId,
        signedPrekey,
        signedPrekeySignature: signature,
        updatedAt: new Date(),
      }),
    );
    if (existing && existing.identityKey !== identityKey) {
      // Old sessions were built on the old identity: those prekeys are no
      // longer ours to hand out.
      await this.prekeyRepo.delete({ deviceId });
    }
    const added = await this.addPrekeys(deviceId, input?.oneTimePreKeys ?? []);
    return { deviceId, ...added };
  }

  /** Adds one-time prekeys, ignoring ids this device already published. */
  async addPrekeys(deviceId: string, prekeys: PrekeyInput[]) {
    if (!Array.isArray(prekeys) || prekeys.length === 0) return this.prekeyStatus(deviceId);
    if (prekeys.length > PREKEY_BATCH_MAX) {
      this.fail('VALIDATION_ERROR', `Send at most ${PREKEY_BATCH_MAX} prekeys at a time.`, HttpStatus.BAD_REQUEST);
    }
    const clean = prekeys.map((p) => ({
      deviceId,
      keyId: this.keyId(p?.keyId, 'prekey id'),
      publicKey: this.key(p?.publicKey, 'prekey'),
    }));
    const available = await this.prekeyRepo.count({ where: { deviceId, consumedAt: IsNull() } });
    if (available + clean.length > PREKEY_MAX_STORED) {
      this.fail('VALIDATION_ERROR', 'This device already has enough prekeys.', HttpStatus.BAD_REQUEST);
    }
    // A retried upload must not fail: the same id is simply already here.
    await this.prekeyRepo
      .createQueryBuilder()
      .insert()
      .values(clean)
      .orIgnore()
      .execute();
    return this.prekeyStatus(deviceId);
  }

  /** What the phone checks to decide whether to top up. */
  async prekeyStatus(deviceId: string) {
    const available = await this.prekeyRepo.count({ where: { deviceId, consumedAt: IsNull() } });
    return { available, lowWater: PREKEY_LOW_WATER, max: PREKEY_MAX_STORED };
  }

  /** True once this device has published an identity key. */
  async hasKeys(deviceId: string): Promise<boolean> {
    return (await this.identityRepo.count({ where: { deviceId } })) > 0;
  }

  /**
   * Everything needed to start a session with every device this person still
   * uses. One one-time prekey per device is spent here and never handed out
   * again — that is the whole point of it.
   */
  async bundlesFor(viewerId: string, userId: string): Promise<{ userId: string; devices: DeviceBundle[] }> {
    if (viewerId !== userId && (await this.blocks.isBlocked(viewerId, userId))) {
      // Same answer as for someone with no keys: a block should not be
      // detectable by asking a different question.
      return { userId, devices: [] };
    }
    const devices = await this.deviceRepo.find({ where: { userId, revokedAt: IsNull() } });
    if (devices.length === 0) return { userId, devices: [] };
    const identities = await this.identityRepo.find({ where: { deviceId: In(devices.map((d) => d.id)) } });

    const out: DeviceBundle[] = [];
    for (const identity of identities) {
      out.push({
        deviceId: identity.deviceId,
        registrationId: identity.registrationId,
        identityKey: identity.identityKey,
        signedPreKey: {
          keyId: identity.signedPrekeyId,
          publicKey: identity.signedPrekey,
          signature: identity.signedPrekeySignature,
        },
        preKey: await this.consumePrekey(identity.deviceId),
      });
    }
    return { userId, devices: out };
  }

  /**
   * Takes one unused prekey and marks it spent, in a single statement so two
   * people starting a session at the same moment cannot receive the same one.
   */
  private async consumePrekey(deviceId: string): Promise<{ keyId: number; publicKey: string } | null> {
    const rows = await this.prekeyRepo.query(
      `UPDATE device_one_time_prekeys SET consumed_at = NOW()
         WHERE id = (
           SELECT id FROM device_one_time_prekeys
            WHERE device_id = $1 AND consumed_at IS NULL
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
         )
       RETURNING key_id, public_key`,
      [deviceId],
    );
    // TypeORM hands back UPDATE ... RETURNING as [rows, affectedCount], not as
    // plain rows. Taking rows[0] blindly yields the array itself, which is
    // truthy — and the caller then hands out a prekey made of undefined.
    const list: { key_id: number; public_key: string }[] =
      Array.isArray(rows) && rows.length === 2 && Array.isArray(rows[0]) && typeof rows[1] === 'number'
        ? rows[0]
        : Array.isArray(rows)
          ? rows
          : [];
    const row = list[0];
    return row ? { keyId: Number(row.key_id), publicKey: String(row.public_key) } : null;
  }

  /** The active devices of these people that can receive encrypted messages. */
  async encryptableDevices(userIds: string[]): Promise<{ deviceId: string; userId: string }[]> {
    if (userIds.length === 0) return [];
    const devices = await this.deviceRepo.find({ where: { userId: In(userIds), revokedAt: IsNull() } });
    if (devices.length === 0) return [];
    const identities = await this.identityRepo.find({ where: { deviceId: In(devices.map((d) => d.id)) } });
    const byDevice = new Map(devices.map((d) => [d.id, d.userId]));
    return identities
      .filter((i) => byDevice.has(i.deviceId))
      .map((i) => ({ deviceId: i.deviceId, userId: byDevice.get(i.deviceId)! }));
  }

  /** A revoked or wiped device leaves no keys behind for anyone to use. */
  async forgetDevice(deviceId: string) {
    await this.prekeyRepo.delete({ deviceId });
    await this.identityRepo.delete({ deviceId });
  }

  /** Drops prekeys that were spent long ago; called by the sweeper. */
  async sweepConsumedPrekeys(): Promise<number> {
    const cutoff = new Date(Date.now() - PREKEY_RETENTION_DAYS * 24 * 3600 * 1000);
    const result = await this.prekeyRepo
      .createQueryBuilder()
      .delete()
      .where('consumed_at IS NOT NULL AND consumed_at < :cutoff', { cutoff })
      .execute();
    return result.affected ?? 0;
  }
}
