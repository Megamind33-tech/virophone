import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { CallsService } from './calls.service';
import { CallsController } from './calls.controller';
import { CallSessionService } from './call-session.service';
import { Call } from '../database/entities/call.entity';
import { CallQuality } from '../database/entities/call-quality.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Device } from '../database/entities/device.entity';
import { BlocksModule } from '../blocks/blocks.module';
import { PushModule } from '../push/push.module';
import { OfflineTrustModule } from '../offline-trust/offline-trust.module';
import { SignalingDeliveryModule } from '../signaling/signaling-delivery.module';

@Module({
  imports: [
    TypeOrmModule.forFeature([
      Call,
      CallQuality,
      ContactMatch,
      ViroConnection,
      Profile,
      PhoneIdentity,
      Device,
    ]),
    BlocksModule,
    PushModule,
    OfflineTrustModule,
    SignalingDeliveryModule,
  ],
  controllers: [CallsController],
  providers: [CallsService, CallSessionService],
  exports: [CallsService, CallSessionService],
})
export class CallsModule {}
