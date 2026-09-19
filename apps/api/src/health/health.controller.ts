import { Controller, Get } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { RedisService } from '../redis/redis.service';
import { MetricsService } from '../metrics/metrics.module';
import { LiveKitService } from '../livekit/livekit.service';

@Controller('health')
export class HealthController {
  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly redis: RedisService,
    private readonly metrics: MetricsService,
    private readonly livekit: LiveKitService,
  ) {}

  @Get('live')
  live() {
    return { status: 'ok', timestamp: new Date().toISOString() };
  }

  @Get('ready')
  async ready() {
    // Call media is as load-bearing as the database here: without LiveKit
    // configured, every internet call rings, connects and then carries no
    // audio, while this endpoint used to keep reporting "ready". It is part
    // of readiness now so the failure is visible before a call is placed.
    const mediaOk = this.livekit.isConfigured();
    try {
      await this.dataSource.query('SELECT 1');
      const redisOk = await this.redis.ping();
      return {
        status: redisOk && mediaOk ? 'ready' : 'not_ready',
        database: 'connected',
        redis: redisOk ? 'connected' : 'disconnected',
        media: mediaOk ? 'configured' : 'unconfigured',
        timestamp: new Date().toISOString(),
      };
    } catch {
      return {
        status: 'not_ready',
        database: 'disconnected',
        media: mediaOk ? 'configured' : 'unconfigured',
        timestamp: new Date().toISOString(),
      };
    }
  }

  @Get('metrics')
  metricsSnapshot() {
    return this.metrics.snapshot();
  }
}
