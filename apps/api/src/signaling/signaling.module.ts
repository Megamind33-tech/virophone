import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { JwtModule } from '@nestjs/jwt';
import { SignalingGateway } from './signaling.gateway';
import { SignalingDeliveryModule } from './signaling-delivery.module';
import { Device } from '../database/entities/device.entity';
import { User } from '../database/entities/user.entity';
import { PresenceModule } from '../presence/presence.module';
import { CallsModule } from '../calls/calls.module';
import { ConferenceModule } from '../conference/conference.module';

@Module({
  imports: [
    SignalingDeliveryModule,
    TypeOrmModule.forFeature([Device, User]),
    JwtModule.register({
      secret: process.env.JWT_ACCESS_SECRET || 'dev_access_secret',
    }),
    PresenceModule,
    CallsModule,
    ConferenceModule,
  ],
  providers: [SignalingGateway],
})
export class SignalingModule {}
