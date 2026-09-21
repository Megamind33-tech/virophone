import { Injectable, Logger, Module, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { MomentsService } from './moments.service';
import { MomentsController } from './moments.controller';
import { PushModule } from '../push/push.module';
import { LiveKitService } from '../livekit/livekit.service';
import { UploadedMediaProvider } from './moment-media.provider';
import { MomentMediaController } from './moment-media.controller';

@Injectable()
export class MomentsClock implements OnModuleInit, OnModuleDestroy {
  private timer?: NodeJS.Timeout;
  private running = false;
  private ticks = 0;
  private readonly logger = new Logger(MomentsClock.name);
  constructor(private readonly moments: MomentsService) {}
  onModuleInit() {
    if (process.env.NODE_ENV !== 'test') this.timer = setInterval(() => void this.tick(), 5000);
  }
  onModuleDestroy() { if (this.timer) clearInterval(this.timer); }
  async tick() {
    if (this.running) return;
    this.running = true;
    try { await this.moments.sweep(); } catch (e) { this.logger.warn(`Moment expiry failed: ${(e as Error).message}`); }
    // Every ten minutes or so: files nothing points at any more.
    if (++this.ticks % 120 === 0) {
      try { await this.moments.sweepOrphanMedia(); } catch (e) { this.logger.warn(`Moment media sweep failed: ${(e as Error).message}`); }
    }
    this.running = false;
  }
}

@Module({ imports: [PushModule], controllers: [MomentsController, MomentMediaController], providers: [MomentsService, MomentsClock, LiveKitService, UploadedMediaProvider], exports: [MomentsService] })
export class MomentsModule {}
