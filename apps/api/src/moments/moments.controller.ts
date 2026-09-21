import { BadRequestException, Body, Controller, Delete, Get, Param, ParseUUIDPipe, Post, Req, UseGuards } from '@nestjs/common';
import { IsIn, IsInt, IsOptional, IsString, Max, MaxLength, Min } from 'class-validator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { MOMENT_AUDIENCES, MOMENT_TYPES, MomentsService } from './moments.service';

class CreateMomentDto {
  @IsIn(MOMENT_TYPES) type!: string;
  @IsOptional() @IsString() @MaxLength(60) text?: string;
  @IsIn(MOMENT_AUDIENCES) visibility!: string;
  @IsInt() @Min(1) @Max(120) durationMinutes!: number;
}
class ExtendMomentDto {
  @IsInt() @Min(1) @Max(60) minutes!: number;
}
type Authed = { user: { sub: string } };

@Controller('api/v1/moments')
@UseGuards(JwtAuthGuard)
export class MomentsController {
  constructor(private readonly moments: MomentsService) {}
  @Get('now') now(@Req() req: Authed) { return this.moments.now(req.user.sub); }
  @Get(':id') get(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.get(req.user.sub, id); }
  @Post() create(@Req() req: Authed, @Body() body: CreateMomentDto) {
    if (body.type === 'CUSTOM' && !body.text?.trim()) throw new BadRequestException('Describe your Moment.');
    return this.moments.create(req.user.sub, body);
  }
  @Post(':id/extend') extend(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string, @Body() body: ExtendMomentDto) {
    return this.moments.extend(req.user.sub, id, body.minutes);
  }
  @Delete(':id') end(@Req() req: Authed, @Param('id', ParseUUIDPipe) id: string) { return this.moments.end(req.user.sub, id); }
}
