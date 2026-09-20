import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { KeysService } from './keys.service';
import { KeysController } from './keys.controller';
import { Device } from '../database/entities/device.entity';
import { DeviceIdentityKey, DeviceOneTimePrekey, MessageEnvelope } from '../database/entities/e2ee.entity';
import { BlocksModule } from '../blocks/blocks.module';
import { AuthModule } from '../auth/auth.module';

@Module({
  imports: [
    TypeOrmModule.forFeature([DeviceIdentityKey, DeviceOneTimePrekey, MessageEnvelope, Device]),
    BlocksModule,
    AuthModule,
  ],
  controllers: [KeysController],
  providers: [KeysService],
  exports: [KeysService],
})
export class E2eeModule {}
