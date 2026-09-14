import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Device } from '../database/entities/device.entity';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class DevicesService {
  constructor(
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
  ) {}

  async register(userId: string, publicKey: string, platform: string, appVersion: string) {
    const device = this.deviceRepo.create({ userId, publicKey, platform, appVersion });
    return this.deviceRepo.save(device);
  }

  async list(userId: string) {
    return this.deviceRepo.find({
      where: { userId },
      select: ['id', 'platform', 'appVersion', 'createdAt', 'lastSeenAt'],
    });
  }

  async revoke(userId: string, deviceId: string) {
    const device = await this.deviceRepo.findOne({ where: { id: deviceId, userId } });
    if (!device) {
      throw new ViroException('NOT_FOUND', 'Device not found.', HttpStatus.NOT_FOUND);
    }
    device.revokedAt = new Date();
    return this.deviceRepo.save(device);
  }
}
