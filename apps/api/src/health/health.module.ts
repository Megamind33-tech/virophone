import { Module } from '@nestjs/common';
import { HealthController } from './health.controller';
import { LiveKitService } from '../livekit/livekit.service';

@Module({
  controllers: [HealthController],
  providers: [LiveKitService],
})
export class HealthModule {}
