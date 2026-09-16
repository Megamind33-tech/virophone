import { Controller, Post, UseGuards, Req } from '@nestjs/common';
import { TurnCredentialService } from './turn-credential.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { Throttle } from '@nestjs/throttler';
import { MetricsService } from '../metrics/metrics.module';

@Controller('api/v1/turn')
@UseGuards(JwtAuthGuard)
export class TurnController {
  constructor(
    private readonly turnService: TurnCredentialService,
    private readonly metrics: MetricsService,
  ) {}

  @Post('credentials')
  @Throttle({ default: { limit: 10, ttl: 60000 } })
  async getCredentials(@Req() req: { user: { sub: string; deviceId: string } }) {
    const creds = await this.turnService.generateCredentials(req.user.sub, req.user.deviceId);
    this.metrics.turnCredentials += 1;
    return creds;
  }
}
