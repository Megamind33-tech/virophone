import { Body, Controller, Param, Post, Req, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { IsIn, IsString } from 'class-validator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { SignalsService, SIGNAL_KINDS } from './signals.service';

type AuthedReq = { user: { sub: string; deviceId: string } };

class SendSignalDto {
  @IsString()
  toUserId!: string;
  @IsIn(SIGNAL_KINDS as unknown as string[])
  kind!: string;
}

class AckSignalDto {
  @IsIn(['RECEIVED', 'PRESENTED', 'OPENED'])
  state!: 'RECEIVED' | 'PRESENTED' | 'OPENED';
}

class RespondSignalDto {
  @IsIn(SIGNAL_KINDS as unknown as string[])
  kind!: string;
}

/**
 * Intimate signals: presence events between two connected people, delivered
 * to the other phone rather than into a chat. Deliberately three routes and
 * no list — a signal is felt when it arrives, not browsed afterwards.
 */
@Controller('api/v1/signals')
@UseGuards(JwtAuthGuard)
export class SignalsController {
  constructor(private readonly signals: SignalsService) {}

  /** Taps are meant to be repeatable; the service groups a burst into one. */
  @Post()
  @Throttle({ default: { limit: 60, ttl: 60_000 } })
  async send(@Req() req: AuthedReq, @Body() body: SendSignalDto) {
    return this.signals.send(req.user.sub, body.toUserId, body.kind);
  }

  @Post(':id/ack')
  async ack(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: AckSignalDto) {
    return this.signals.ack(req.user.sub, id, body.state);
  }

  @Post(':id/respond')
  @Throttle({ default: { limit: 30, ttl: 60_000 } })
  async respond(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: RespondSignalDto) {
    return this.signals.respond(req.user.sub, id, body.kind);
  }
}
