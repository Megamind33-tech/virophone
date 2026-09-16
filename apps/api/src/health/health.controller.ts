import { Controller, Get } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { RedisService } from '../redis/redis.service';
import { MetricsService } from '../metrics/metrics.module';

@Controller('health')
export class HealthController {
  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly redis: RedisService,
    private readonly metrics: MetricsService,
  ) {}

  @Get('live')
  live() {
    return { status: 'ok', timestamp: new Date().toISOString() };
  }

  @Get('ready')
  async ready() {
    try {
      await this.dataSource.query('SELECT 1');
      const redisOk = await this.redis.ping();
      return {
        status: redisOk ? 'ready' : 'not_ready',
        database: 'connected',
        redis: redisOk ? 'connected' : 'disconnected',
        timestamp: new Date().toISOString(),
      };
    } catch {
      return { status: 'not_ready', database: 'disconnected', timestamp: new Date().toISOString() };
    }
  }

  @Get('metrics')
  metricsSnapshot() {
    return this.metrics.snapshot();
  }
}
