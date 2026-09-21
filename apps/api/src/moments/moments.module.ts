import { Injectable, Logger, Module, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { MomentsService } from './moments.service';
import { MomentsController } from './moments.controller';
import { PushModule } from '../push/push.module';

@Injectable()
export class MomentsClock implements OnModuleInit, OnModuleDestroy {
  private timer?: NodeJS.Timeout;
  private running = false;
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
    finally { this.running = false; }
  }
}

@Module({ imports: [PushModule], controllers: [MomentsController], providers: [MomentsService, MomentsClock] })
export class MomentsModule {}
