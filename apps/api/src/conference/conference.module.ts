import { Module } from '@nestjs/common';
import { ConferenceService } from './conference.service';
import { ConferenceController } from './conference.controller';
import { LiveKitService } from '../livekit/livekit.service';

@Module({
  controllers: [ConferenceController],
  providers: [ConferenceService, LiveKitService],
  exports: [ConferenceService],
})
export class ConferenceModule {}
