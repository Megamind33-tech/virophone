import { Controller, Post, Get, Param, Body, UseGuards, Req } from '@nestjs/common';
import { IsArray, IsString, IsOptional, ArrayMaxSize, MaxLength } from 'class-validator';
import { ConferenceService } from './conference.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';

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
  constructor(private readonly conferenceService: ConferenceService) {}

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
}
