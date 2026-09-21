import { BadRequestException, Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Post, Req, UseGuards } from '@nestjs/common';
import { IsBoolean, IsIn, IsInt, IsOptional, IsString, Max, MaxLength, Min, MinLength } from 'class-validator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { MOMENT_AUDIENCES, MOMENT_REACTIONS, MOMENT_TYPES, MomentsService } from './moments.service';

class CreateMomentDto {
  @IsIn(MOMENT_TYPES) type!: string;
  @IsOptional() @IsString() @MaxLength(60) text?: string;
  @IsIn(MOMENT_AUDIENCES) visibility!: string;
  @IsInt() @Min(1) @Max(120) durationMinutes!: number;
}
class ExtendMomentDto {
  @IsInt() @Min(1) @Max(60) minutes!: number;
}
class MomentMessageDto {
  @IsString() @MinLength(1) @MaxLength(500) body!: string;
}
class ReactDto {
  // null removes the caller's reaction; anything sent must be in the set.
  @IsOptional() @IsIn(MOMENT_REACTIONS.concat([null as unknown as string])) emoji?: string | null;
}
class KnockResponseDto {
  @IsBoolean() accept!: boolean;
}
class InviteDto {
  @IsString() userId!: string;
}
type Authed = { user: { sub: string } };

@Controller('api/v1/moments')
@UseGuards(JwtAuthGuard)
export class MomentsController {
  constructor(private readonly moments: MomentsService) {}

  @Get('now') now(@Req() req: Authed) { return this.moments.now(req.user.sub); }
  // Declared before the :id routes so 'invitations' is never parsed as an id.
  @Get('invitations') invitations(@Req() req: Authed) { return this.moments.invitations(req.user.sub); }
  @Delete('invitations/:invitationId') decline(@Req() req: Authed, @Param('invitationId', ParseUUIDPipe) invitationId: string) {
    return this.moments.declineInvitation(req.user.sub, invitationId);
  }
  @Get(':id') get(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.get(req.user.sub, id); }
  @Post() create(@Req() req: Authed, @Body() body: CreateMomentDto) {
    if (body.type === 'CUSTOM' && !body.text?.trim()) throw new BadRequestException('Describe your Moment.');
    return this.moments.create(req.user.sub, body);
  }
  @Post(':id/extend') extend(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: ExtendMomentDto) {
    return this.moments.extend(req.user.sub, id, body.minutes);
  }
  @Delete(':id') end(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.end(req.user.sub, id); }

  // Room actions are 200, not Nest's POST-default 201: none of them creates a
  // fetchable resource. Creating a Moment or a room message does, and stays 201.
  @Post(':id/join') @HttpCode(200) join(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.join(req.user.sub, id); }
  @Post(':id/leave') @HttpCode(200) leave(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.leave(req.user.sub, id); }
  @Get(':id/room') room(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.room(req.user.sub, id); }
  @Post(':id/messages') message(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: MomentMessageDto) {
    return this.moments.message(req.user.sub, id, body.body);
  }
  @Post(':id/messages/:messageId/react') @HttpCode(200) react(
    @Req() req: Authed,
    @Param('id', ParseUUIDPipe) id: string,
    @Param('messageId', ParseUUIDPipe) messageId: string,
    @Body() body: ReactDto,
  ) { return this.moments.react(req.user.sub, id, messageId, body.emoji ?? null); }

  @Post(':id/knock') @HttpCode(200) knock(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.knock(req.user.sub, id); }
  @Get(':id/knocks') knocks(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.knocks(req.user.sub, id); }
  @Post(':id/knocks/:knockerId/respond') @HttpCode(200) respond(
    @Req() req: Authed,
    @Param('id', ParseUUIDPipe) id: string,
    @Param('knockerId', ParseUUIDPipe) knockerId: string,
    @Body() body: KnockResponseDto,
  ) { return this.moments.respondToKnock(req.user.sub, id, knockerId, body.accept); }

  @Post(':id/invites') @HttpCode(200) invite(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: InviteDto) {
    return this.moments.invite(req.user.sub, id, body.userId);
  }
}
