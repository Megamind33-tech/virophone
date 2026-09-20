import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { DevicesService } from './devices.service';
import { DevicesController, DeviceLinkController } from './devices.controller';
import { DeviceLinkService } from './device-link.service';
import { DeviceLinkRequest } from '../database/entities/device-link-request.entity';
import { Profile } from '../database/entities/profile.entity';
import { AuthModule } from '../auth/auth.module';
import { Device } from '../database/entities/device.entity';
import { E2eeModule } from '../e2ee/e2ee.module';

@Module({
  imports: [TypeOrmModule.forFeature([Device, DeviceLinkRequest, Profile]), AuthModule, E2eeModule],
  controllers: [DevicesController, DeviceLinkController],
  providers: [DevicesService, DeviceLinkService],
  exports: [DevicesService],
})
export class DevicesModule {}
