import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { IsNull, Repository } from 'typeorm';
import { Device } from '../database/entities/device.entity';
import { KeysService } from '../e2ee/keys.service';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class DevicesService {
  constructor(
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    private readonly keys: KeysService,
  ) {}

  async register(userId: string, publicKey: string, platform: string, appVersion: string) {
    const device = this.deviceRepo.create({ userId, publicKey, platform, appVersion });
    return this.deviceRepo.save(device);
  }

  async list(userId: string) {
    return this.deviceRepo.find({
      where: { userId, revokedAt: IsNull() },
      select: ['id', 'platform', 'appVersion', 'createdAt', 'lastSeenAt'],
      order: { lastSeenAt: 'DESC' },
    });
  }

  async revoke(userId: string, deviceId: string) {
    const device = await this.deviceRepo.findOne({ where: { id: deviceId, userId } });
    if (!device) {
      throw new ViroException('NOT_FOUND', 'Device not found.', HttpStatus.NOT_FOUND);
    }
    device.revokedAt = new Date();
    const saved = await this.deviceRepo.save(device);
    // A revoked device must stop being a destination: its published keys go,
    // so nobody starts a new session with something this person no longer has.
    await this.keys.forgetDevice(device.id);
    return saved;
  }
}
