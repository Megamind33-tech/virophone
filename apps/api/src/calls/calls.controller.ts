import { Controller, Post, Get, Param, Body, UseGuards, Req } from '@nestjs/common';
import { CallsService } from './calls.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty, IsOptional, IsNumber, IsBoolean } from 'class-validator';
import { MetricsService } from '../metrics/metrics.module';

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
    return result;
  }

  @Get('history')
  async history(@Req() req: { user: { sub: string } }) {
    return this.callsService.history(req.user.sub);
  }

  @Get('history')
  async history(@Req() req: { user: { sub: string } }) {
    return this.callsService.history(req.user.sub);
  }

  @Post(':id/end')
  async end(@Req() req: { user: { sub: string } }, @Param('id') id: string) {
    return this.callsService.endCall(id, req.user.sub);
  }

  @Post(':id/events')
  async events(@Param('id') id: string, @Body() body: CallQualityDto) {
    await this.callsService.recordQuality(id, body);
    return { received: true };
  }
}
