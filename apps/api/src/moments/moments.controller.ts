import { BadRequestException, Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Patch, Post, Req, UseGuards } from '@nestjs/common';
import { ArrayMaxSize, IsArray, IsBoolean, IsIn, IsInt, IsOptional, IsString, IsUUID, Max, MaxLength, Min, ValidateNested } from 'class-validator';
import { Type } from 'class-transformer';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { MOMENT_AUDIENCES, MOMENT_REACTIONS, MOMENT_TYPES, MomentsService } from './moments.service';
import { MOMENT_INTENTS, MOMENT_MODULES, MOMENT_SCENES, RoomChange } from './room-engine';

class CreateMomentDto {
  @IsIn(MOMENT_TYPES) type!: string;
  @IsOptional() @IsString() @MaxLength(60) text?: string;
  @IsIn(MOMENT_AUDIENCES) visibility!: string;
  @IsInt() @Min(1) @Max(120) durationMinutes!: number;
  /** What people are coming together to do; shapes the room it opens into. */
  @IsOptional() @IsIn(MOMENT_INTENTS as unknown as string[]) intent?: (typeof MOMENT_INTENTS)[number];
}
/**
 * One change to what a room is. Only the fields its op needs are read; the
 * engine refuses anything that does not make sense.
 */
class RoomChangeDto {
  @IsIn(['TRANSFORM', 'ADD', 'REMOVE', 'SCENE']) op!: RoomChange['op'];
  @IsOptional() @IsIn(MOMENT_INTENTS as unknown as string[]) intent?: string;
  @IsOptional() @IsIn(MOMENT_MODULES as unknown as string[]) module?: string;
  // null gives the scene back to the activity.
  @IsOptional() @IsIn((MOMENT_SCENES as unknown as (string | null)[]).concat([null])) scene?: string | null;
}
class ExtendMomentDto {
  @IsInt() @Min(1) @Max(60) minutes!: number;
}
/** One sealed copy of a room message, addressed to one device in the room. */
class MomentEnvelopeDto {
  @IsUUID() deviceId!: string;
  // libsignal ciphertext, base64. Generous, because a prekey message that
  // opens a session carries a Kyber encapsulation and is much larger than the
  // ratchet messages that follow it.
  @IsString() @MaxLength(8192) ciphertext!: string;
  @IsOptional() @IsInt() @Min(1) @Max(3) type?: number;
}
/**
 * Either a readable body or sealed copies — the client sends whichever it
 * could manage, and the server does not mind which, only that there is one.
 */
class MomentMessageDto {
  @IsOptional() @IsString() @MaxLength(500) body?: string;
  @IsOptional() @IsArray() @ArrayMaxSize(64) @ValidateNested({ each: true })
  @Type(() => MomentEnvelopeDto) envelopes?: MomentEnvelopeDto[];
}
class VisibilityDto {
  @IsIn(MOMENT_AUDIENCES) visibility!: string;
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
type Authed = { user: { sub: string; deviceId: string } };

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
  @Patch(':id/visibility') @HttpCode(200) visibility(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: VisibilityDto) {
    return this.moments.setVisibility(req.user.sub, id, body.visibility);
  }
  /** A reaction to the Moment itself. No need to be in the room to leave one. */
  @Post(':id/react') @HttpCode(200) cheer(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: ReactDto) {
    return this.moments.cheer(req.user.sub, id, body.emoji ?? null);
  }
  @Delete(':id') end(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.end(req.user.sub, id); }

  // Room actions are 200, not Nest's POST-default 201: none of them creates a
  // fetchable resource. Creating a Moment or a room message does, and stays 201.
  @Post(':id/join') @HttpCode(200) join(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.join(req.user.sub, req.user.deviceId, id); }
  @Post(':id/leave') @HttpCode(200) leave(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.leave(req.user.sub, id); }
  /** Changes what the room is, for everyone in it, without anyone leaving. */
  @Post(':id/state') @HttpCode(200) changeRoom(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: RoomChangeDto) {
    return this.moments.changeRoom(req.user.sub, id, this.toChange(body));
  }
  /** Admission to the room's live faces and voices; turns nothing on by itself. */
  @Post(':id/presence') @HttpCode(200) presence(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.presence(req.user.sub, id); }
  @Get(':id/room') room(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.room(req.user.sub, req.user.deviceId, id); }
  @Post(':id/messages') message(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: MomentMessageDto) {
    return this.moments.message(req.user.sub, req.user.deviceId, id, body);
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

  /** The request, as the one change it describes — or a plain refusal. */
  private toChange(body: RoomChangeDto): RoomChange {
    switch (body.op) {
      case 'TRANSFORM':
        if (!body.intent) throw new BadRequestException('Say what the room becomes.');
        return { op: 'TRANSFORM', intent: body.intent as never };
      case 'ADD':
      case 'REMOVE':
        if (!body.module) throw new BadRequestException('Say what to add or take away.');
        return { op: body.op, module: body.module as never };
      case 'SCENE':
        return { op: 'SCENE', scene: (body.scene ?? null) as never };
      default:
        throw new BadRequestException('Unknown change.');
    }
  }
}
