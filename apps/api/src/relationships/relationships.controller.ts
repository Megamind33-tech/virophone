import { Body, Controller, Delete, Get, Headers, Param, Patch, Post, Put, Query, Req, UseGuards } from '@nestjs/common';
import {
  ArrayMaxSize, IsArray, IsBoolean, IsIn, IsInt, IsISO8601, IsNotEmpty, IsOptional, IsString, Max, MaxLength, Min,
  ValidateIf,
} from 'class-validator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { RelationshipsService } from './relationships.service';
import { LoopsService } from './loops.service';

type AuthedReq = { user: { sub: string; deviceId?: string } };

class RelationshipDto {
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() subjectUserId?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() @MaxLength(20) subjectPhone?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() @MaxLength(120) displayName?: string | null;
  @IsString() @IsOptional() @IsIn(['PERSONAL', 'PROFESSIONAL']) category?: string;
  @IsString() @IsOptional() @MaxLength(24) relationshipType?: string;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() @MaxLength(40) customLabel?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() vibe?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() targetCadence?: string | null;
  @IsInt() @IsOptional() @Min(1) @Max(31) targetCount?: number;
  @ValidateIf((_, v) => v !== null) @IsInt() @IsOptional() @Min(1) @Max(365) targetEveryDays?: number | null;
  @ValidateIf((_, v) => v !== null) @IsInt() @IsOptional() @Min(0) @Max(6) targetWeekday?: number | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() @MaxLength(80) targetLabel?: string | null;
  @IsBoolean() @IsOptional() remindersEnabled?: boolean;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() @MaxLength(4000) notes?: string | null;
}

class DateDto {
  @IsString() @IsNotEmpty() kind!: string;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() @MaxLength(80) label?: string | null;
  @IsInt() @Min(1) @Max(12) month!: number;
  @IsInt() @Min(1) @Max(31) day!: number;
  @ValidateIf((_, v) => v !== null) @IsInt() @IsOptional() year?: number | null;
  @IsInt() @IsOptional() @Min(0) @Max(30) remindDaysBefore?: number;
}

class CommitmentDto {
  @IsString() @IsNotEmpty() @MaxLength(280) text!: string;
  @IsISO8601() dueAt!: string;
  @IsString() @IsOptional() kind?: string;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() relationshipId?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() subjectUserId?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() subjectPhone?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() displayName?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() conversationId?: string | null;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() messageId?: string | null;
}

class CommitmentPatchDto {
  @IsString() @IsOptional() @IsIn(['OPEN', 'DONE', 'DISMISSED']) status?: string;
  @IsISO8601() @IsOptional() dueAt?: string;
  @IsString() @IsOptional() @MaxLength(280) text?: string;
}

class LoopDto {
  @IsString() @IsOptional() conversationId?: string;
  @IsString() @IsOptional() toUserId?: string;
  @IsString() @IsNotEmpty() @MaxLength(80) title!: string;
  @IsString() @IsNotEmpty() @MaxLength(280) prompt!: string;
  @IsString() @IsNotEmpty() frequency!: string;
  @ValidateIf((_, v) => v !== null) @IsInt() @IsOptional() @Min(0) @Max(127) daysMask?: number | null;
  @IsString() @IsOptional() timeOfDay?: string;
  @IsString() @IsOptional() timezone?: string;
  @IsString() @IsOptional() responseKind?: string;
  @ValidateIf((_, v) => v !== null) @IsArray() @IsOptional() choices?: string[] | null;
  @IsBoolean() @IsOptional() reciprocal?: boolean;
}

class LoopPatchDto {
  @IsBoolean() @IsOptional() active?: boolean;
  @IsString() @IsOptional() @MaxLength(80) title?: string;
  @IsString() @IsOptional() @MaxLength(280) prompt?: string;
  @IsString() @IsOptional() timeOfDay?: string;
  @IsString() @IsOptional() frequency?: string;
  @ValidateIf((_, v) => v !== null) @IsInt() @IsOptional() daysMask?: number | null;
}

class LoopAnswerDto {
  @IsString() @IsNotEmpty() kind!: string;
  // Empty for an encrypted answer: the words are inside the envelopes.
  @IsString() @IsOptional() @MaxLength(1000) text?: string;
  @IsString() @IsOptional() mediaId?: string;
  /**
   * One sealed copy per device of everyone in the conversation. The server
   * still decides when an answer may be seen; it simply cannot read the
   * thing it is withholding.
   */
  @IsArray() @IsOptional() @ArrayMaxSize(512)
  envelopes?: { deviceId: string; ciphertext: string; type?: number }[];
}

class CheckinDto {
  @IsString() @IsOptional() @MaxLength(120) note?: string;
}

class SettingsDto {
  @IsString() @IsOptional() timezone?: string;
  @IsString() @IsOptional() quietStart?: string;
  @IsString() @IsOptional() quietEnd?: string;
  @IsBoolean() @IsOptional() briefEnabled?: boolean;
  @IsString() @IsOptional() briefTime?: string;
  @IsString() @IsOptional() @IsIn(['LOW', 'NORMAL', 'HIGH']) frequency?: string;
  @IsBoolean() @IsOptional() personalReminders?: boolean;
  @IsBoolean() @IsOptional() professionalReminders?: boolean;
  @IsBoolean() @IsOptional() dateReminders?: boolean;
  @IsBoolean() @IsOptional() loopNotifications?: boolean;
  @IsBoolean() @IsOptional() achievementNotifications?: boolean;
}

/**
 * Private relationship intelligence. Every route here is scoped to the
 * caller's own rows; nothing reads another user's targets, notes or dates.
 */
@Controller('api/v1/relationships')
@UseGuards(JwtAuthGuard)
export class RelationshipsController {
  constructor(private readonly svc: RelationshipsService) {}

  @Get('overview')
  overview(@Req() req: AuthedReq, @Headers('x-timezone') tz?: string) {
    return this.svc.overview(req.user.sub, tz);
  }

  @Get('nudges')
  nudges(@Req() req: AuthedReq, @Headers('x-timezone') tz?: string) {
    return this.svc.nudges(req.user.sub, tz);
  }

  @Get('for-subject')
  forSubject(
    @Req() req: AuthedReq,
    @Query('userId') userId?: string,
    @Query('phone') phone?: string,
    @Headers('x-timezone') tz?: string,
  ) {
    return this.svc.forSubject(req.user.sub, userId || null, phone || null, tz);
  }

  @Get('settings')
  settings(@Req() req: AuthedReq, @Headers('x-timezone') tz?: string) {
    return this.svc.settings(req.user.sub, tz);
  }

  @Put('settings')
  updateSettings(@Req() req: AuthedReq, @Body() body: SettingsDto) {
    return this.svc.updateSettings(req.user.sub, body);
  }

  @Get('commitments/all')
  async commitments(@Req() req: AuthedReq, @Query('status') status?: string) {
    const list = await this.svc.listCommitments(req.user.sub, status);
    return list.map((c) => this.svc.commitmentDto(c));
  }

  @Post('commitments')
  async addCommitment(@Req() req: AuthedReq, @Body() body: CommitmentDto) {
    return this.svc.commitmentDto(await this.svc.addCommitment(req.user.sub, body));
  }

  @Patch('commitments/:cid')
  async updateCommitment(@Req() req: AuthedReq, @Param('cid') cid: string, @Body() body: CommitmentPatchDto) {
    return this.svc.commitmentDto(await this.svc.updateCommitment(req.user.sub, cid, body));
  }

  @Put('achievements/:key/share')
  share(@Req() req: AuthedReq, @Param('key') key: string, @Body() body: { shared?: boolean }) {
    return this.svc.shareAchievement(req.user.sub, key, body?.shared !== false);
  }

  @Delete('dates/:dateId')
  removeDate(@Req() req: AuthedReq, @Param('dateId') dateId: string) {
    return this.svc.removeDate(req.user.sub, dateId);
  }

  @Put()
  upsert(@Req() req: AuthedReq, @Body() body: RelationshipDto) {
    return this.svc.upsert(req.user.sub, body);
  }

  @Delete(':id')
  remove(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.svc.remove(req.user.sub, id);
  }

  @Get(':id/timeline')
  timeline(@Req() req: AuthedReq, @Param('id') id: string, @Headers('x-timezone') tz?: string) {
    return this.svc.timeline(req.user.sub, id, tz);
  }

  @Post(':id/dates')
  addDate(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: DateDto) {
    return this.svc.addDate(req.user.sub, id, body);
  }

  @Post(':id/checkins')
  checkIn(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: CheckinDto) {
    return this.svc.checkIn(req.user.sub, id, body.note);
  }
}

/** Loops are shared by design: both people take part. */
@Controller('api/v1/loops')
@UseGuards(JwtAuthGuard)
export class LoopsController {
  constructor(private readonly loops: LoopsService) {}

  @Post()
  create(@Req() req: AuthedReq, @Body() body: LoopDto) {
    return this.loops.create(req.user.sub, body);
  }

  @Get()
  list(@Req() req: AuthedReq, @Query('conversationId') conversationId?: string) {
    return conversationId ? this.loops.listForConversation(req.user.sub, conversationId) : this.loops.listMine(req.user.sub);
  }

  @Patch(':id')
  update(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: LoopPatchDto) {
    return this.loops.update(req.user.sub, id, body);
  }

  @Delete(':id')
  remove(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.loops.remove(req.user.sub, id);
  }

  @Post(':id/answer')
  answer(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: LoopAnswerDto) {
    return this.loops.answer(req.user.sub, req.user.deviceId ?? null, id, body);
  }

  @Get(':id/history')
  history(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.loops.history(req.user.sub, id);
  }
}
