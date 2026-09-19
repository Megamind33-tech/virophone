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
import { IsArray, IsString, IsOptional, ArrayMaxSize, MaxLength } from 'class-validator';
import { ConferenceService } from './conference.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { LiveKitService } from '../livekit/livekit.service';

class CreateConferenceDto {
  @IsArray()
  @IsOptional()
  @ArrayMaxSize(16)
  @IsString({ each: true })
  inviteeUserIds?: string[];

  @IsString()
  @IsOptional()
  @MaxLength(120)
  title?: string;
}

@Controller('api/v1/conferences')
@UseGuards(JwtAuthGuard)
export class ConferenceController {
  constructor(
    private readonly conferenceService: ConferenceService,
    private readonly liveKitService: LiveKitService,
  ) {}

  @Post()
  async create(
    @Req() req: { user: { sub: string } },
    @Body() body: CreateConferenceDto,
  ) {
    return this.conferenceService.createRoom(
      req.user.sub,
      body.inviteeUserIds ?? [],
      body.title,
    );
  }

  @Get(':id/participants')
  async participants(@Param('id') id: string) {
    return this.conferenceService.members(id);
  }

  /**
   * Issues a room-scoped LiveKit token for the group call's media — same
   * secret-never-leaves-the-server pattern as the 1:1 call endpoint. Gated
   * on the conference's own allow-list (canJoin), not membership, since a
   * device may fetch this before or independently of the WSS conf.join.
   */
  @Post(':id/livekit-token')
  async livekitToken(
    @Req() req: { user: { sub: string } },
    @Param('id') id: string,
  ) {
    const meta = await this.conferenceService.getMeta(id);
    if (!meta) {
      throw new NotFoundException('Conference not found or expired.');
    }
    const canJoin = await this.conferenceService.canJoin(id, req.user.sub);
    if (!canJoin) {
      throw new ForbiddenException('Not invited to this conference.');
    }
    return this.liveKitService.generateTokenForRoom(
      this.liveKitService.roomNameForConference(id),
      req.user.sub,
    );
  }
}
