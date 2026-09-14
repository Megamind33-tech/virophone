import { Module } from '@nestjs/common';
import { PresenceService } from './presence.service';
import { PresenceController } from './presence.controller';
import { BlocksModule } from '../blocks/blocks.module';

@Module({
  imports: [BlocksModule],
  controllers: [PresenceController],
  providers: [PresenceService],
  exports: [PresenceService],
})
export class PresenceModule {}
