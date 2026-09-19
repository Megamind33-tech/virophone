import {
  Controller,
  Post,
  Get,
  Param,
  Body,
  UseGuards,
  Req,
  ForbiddenException,
  NotFoundException,
} from '@nestjs/common';
import { CallsService } from './calls.service';
import { CallSessionService } from './call-session.service';
import { SignalingDeliveryService } from '../signaling/signaling-delivery.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty, IsOptional, IsNumber, IsBoolean } from 'class-validator';
import { MetricsService } from '../metrics/metrics.module';
import { LiveKitService } from '../livekit/livekit.service';

class AuthorizeCallDto {
  @IsString()
  @IsNotEmpty()
  targetUserId!: string;

  @IsString()
  @IsOptional()
  preferredRoute?: string;

  @IsString()
  @IsOptional()
  offlineTicket?: string;
}

class InviteToCallDto {
  @IsString()
  @IsNotEmpty()
  userId!: string;
}

class CallQualityDto {
  @IsNumber()
  @IsOptional()
  latency?: number;

  @IsNumber()
  @IsOptional()
  jitter?: number;

  @IsNumber()
  @IsOptional()
  packetLoss?: number;

  @IsNumber()
  @IsOptional()
  bitrate?: number;

  @IsString()
  @IsOptional()
  codec?: string;

  @IsString()
  @IsOptional()
  route?: string;

  @IsBoolean()
  @IsOptional()
  relayed?: boolean;
}

@Controller('api/v1/calls')
@UseGuards(JwtAuthGuard)
export class CallsController {
  constructor(
    private readonly callsService: CallsService,
    private readonly metrics: MetricsService,
    private readonly signalingDelivery: SignalingDeliveryService,
    private readonly callSessionService: CallSessionService,
    private readonly liveKitService: LiveKitService,
  ) {}

  @Post('authorize')
  async authorize(
    @Req() req: { user: { sub: string; deviceId: string } },
    @Body() body: AuthorizeCallDto,
  ) {
    const result = await this.callsService.authorize(
      req.user.sub,
      req.user.deviceId,
      body.targetUserId,
      body.preferredRoute,
      body.offlineTicket,
    );
    this.metrics.callsAuthorized += 1;
    if (result.authorized && result.sessionMaterial?.calleeDeviceId) {
      const caller = await this.callsService.getCallerPresentation(req.user.sub);
      this.signalingDelivery.notifyIncomingCall({
        calleeDeviceId: result.sessionMaterial.calleeDeviceId,
        callId: result.callId,
        callerUserId: req.user.sub,
        callerDeviceId: req.user.deviceId,
        callerPhoneE164: caller.callerPhoneE164,
        callerDisplayName: caller.callerDisplayName,
      });
    }
    return result;
  }

  @Get('history')
  async history(@Req() req: { user: { sub: string } }) {
    return this.callsService.history(req.user.sub);
  }

  @Post(':id/end')
  async end(@Req() req: { user: { sub: string } }, @Param('id') id: string) {
    return this.callsService.endCall(id, req.user.sub);
  }

  /**
   * Issues a room-scoped LiveKit token for realtime media. Only a current
   * participant (caller or one of the callee's devices) on a still-live call
   * session may obtain one — the same authorization CallSessionService
   * already enforces for WSS signaling (see SignalingGateway.handleSignaling).
   * LIVEKIT_API_SECRET is used only to sign the JWT here; it never appears
   * in the response.
   */
  @Post(':id/livekit-token')
  async livekitToken(
    @Req() req: { user: { sub: string; deviceId: string } },
    @Param('id') id: string,
  ) {
    const session = await this.callSessionService.getSession(id);
    if (!session) {
      throw new NotFoundException('Call not found or already ended.');
    }
    const isParticipant = await this.callSessionService.isParticipant(
      id,
      req.user.sub,
      req.user.deviceId,
    );
    if (!isParticipant) {
      throw new ForbiddenException('Not a participant on this call.');
    }
    return this.liveKitService.generateToken(id, req.user.sub);
  }

  /**
   * Adds another person to a call in progress. Any current participant may
   * add someone, subject to the same block/contact checks as placing a call.
   */
  @Post(':id/invite')
  async invite(
    @Req() req: { user: { sub: string; deviceId: string } },
    @Param('id') id: string,
    @Body() body: InviteToCallDto,
  ) {
    return this.callsService.inviteToCall(
      req.user.sub,
      req.user.deviceId,
      id,
      body.userId,
    );
  }

  @Post(':id/events')
  async events(@Param('id') id: string, @Body() body: CallQualityDto) {
    await this.callsService.recordQuality(id, body);
    return { received: true };
  }
}
