import { Controller, Get, Post, Body, Param, UseGuards, Req } from '@nestjs/common';
import { PresenceService, PresenceState } from './presence.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsIn } from 'class-validator';

class UpdatePresenceDto {
  @IsString()
  @IsIn(['OFFLINE', 'ONLINE', 'LOCAL', 'BUSY'])
  state!: PresenceState;
}

@Controller('api/v1/presence')
@UseGuards(JwtAuthGuard)
export class PresenceController {
  constructor(private readonly presenceService: PresenceService) {}

  @Post()
  async updatePresence(
    @Req() req: { user: { sub: string } },
    @Body() body: UpdatePresenceDto,
  ) {
    await this.presenceService.setPresence(req.user.sub, body.state);
    return { success: true };
  }

  @Get(':userId')
  async getPresence(
    @Req() req: { user: { sub: string } },
    @Param('userId') targetUserId: string,
  ) {
    const result = await this.presenceService.getPresenceForAuthorizedViewer(
      req.user.sub,
      targetUserId,
    );
    return result || { state: 'OFFLINE' };
  }
}
