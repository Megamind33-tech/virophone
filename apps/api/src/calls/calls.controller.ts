import { Controller, Post, Param, Body, UseGuards, Req } from '@nestjs/common';
import { CallsService } from './calls.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty, IsOptional } from 'class-validator';

class AuthorizeCallDto {
  @IsString()
  @IsNotEmpty()
  targetUserId!: string;

  @IsString()
  @IsOptional()
  preferredRoute?: string;
}

@Controller('api/v1/calls')
@UseGuards(JwtAuthGuard)
export class CallsController {
  constructor(private readonly callsService: CallsService) {}

  @Post('authorize')
  async authorize(
    @Req() req: { user: { sub: string; deviceId: string } },
    @Body() body: AuthorizeCallDto,
  ) {
    return this.callsService.authorize(
      req.user.sub,
      req.user.deviceId,
      body.targetUserId,
      body.preferredRoute,
    );
  }

  @Post(':id/end')
  async end(@Req() req: { user: { sub: string } }, @Param('id') id: string) {
    return this.callsService.endCall(id, req.user.sub);
  }

  @Post(':id/events')
  async events(@Param('id') _id: string) {
    return { received: true };
  }
}
