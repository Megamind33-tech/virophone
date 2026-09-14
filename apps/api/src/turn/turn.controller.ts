import { Controller, Post, UseGuards, Req } from '@nestjs/common';
import { TurnCredentialService } from './turn-credential.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { Throttle } from '@nestjs/throttler';

@Controller('api/v1/turn')
@UseGuards(JwtAuthGuard)
export class TurnController {
  constructor(private readonly turnService: TurnCredentialService) {}

  @Post('credentials')
  @Throttle({ default: { limit: 10, ttl: 60000 } })
  async getCredentials(@Req() req: { user: { sub: string; deviceId: string } }) {
    return this.turnService.generateCredentials(req.user.sub, req.user.deviceId);
  }
}
