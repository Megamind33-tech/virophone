import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { JwtModule } from '@nestjs/jwt';
import { SignalingGateway } from './signaling.gateway';
import { Device } from '../database/entities/device.entity';
import { User } from '../database/entities/user.entity';
import { PresenceModule } from '../presence/presence.module';

@Module({
  imports: [
    TypeOrmModule.forFeature([Device, User]),
    JwtModule.register({
      secret: process.env.JWT_ACCESS_SECRET || 'dev_access_secret',
    }),
    PresenceModule,
  ],
  providers: [SignalingGateway],
})
export class SignalingModule {}
