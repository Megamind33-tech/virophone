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
import { Device } from '../database/entities/device.entity';
import { BlocksModule } from '../blocks/blocks.module';
import { PushModule } from '../push/push.module';

@Module({
  imports: [
    TypeOrmModule.forFeature([Call, CallQuality, ContactMatch, ViroConnection, Profile, Device]),
    BlocksModule,
    PushModule,
  ],
  controllers: [CallsController],
  providers: [CallsService, CallSessionService],
  exports: [CallsService, CallSessionService],
})
export class CallsModule {}
