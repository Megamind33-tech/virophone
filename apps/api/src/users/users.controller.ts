import { AboutYouService } from './about-you.service';
import {
  Controller,
  Get,
  Patch,
  Delete,
  Post,
  Body,
  UseGuards,
  Req,
  Param,
  Query,
  UploadedFile,
  UseInterceptors,
  Put,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { memoryStorage } from 'multer';
import { UsersService } from './users.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsOptional, IsIn, IsBoolean, MaxLength, ValidateIf, IsArray, ArrayMaxSize } from 'class-validator';

class UpdateMeDto {
  @IsString()
  @IsOptional()
  @MaxLength(100)
  displayName?: string;

  @IsString()
  @IsOptional()
  avatarUrl?: string | null;

  @IsString()
  @IsOptional()
  viroId?: string;

  @IsString()
  @IsOptional()
  @IsIn(['CONNECTIONS_ONLY', 'EXACT_ID_ALLOWED'])
  allowCallsFromViroId?: string;

  /** Let an exact, verified email match find me in Find people. */
  @IsBoolean()
  @IsOptional()
  discoverableByEmail?: boolean;

  /** Marks the one-time name + Viro ID step done (needs a name and a Viro ID). */
  @IsBoolean()
  @IsOptional()
  completeProfile?: boolean;

  /** A short line about yourself. Null or "" clears it. */
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @IsOptional()
  @MaxLength(139)
  about?: string | null;

  @IsString() @IsOptional() @IsIn(['EVERYONE', 'CONTACTS', 'NOBODY']) aboutVisibility?: string;
  @IsString() @IsOptional() @IsIn(['EVERYONE', 'CONTACTS', 'NOBODY']) photoVisibility?: string;
  @IsString() @IsOptional() @IsIn(['EVERYONE', 'CONTACTS', 'NOBODY']) lastSeenVisibility?: string;

  /** "YYYY-MM-DD", or null to remove it. Checked properly in the service. */
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @IsOptional()
  @MaxLength(10)
  birthDate?: string | null;

  /** Who may see the birthday — the day and month, never the year. */
  @IsString() @IsOptional() @IsIn(['EVERYONE', 'CONTACTS', 'NOBODY']) birthdayVisibility?: string;
}

/**
 * One topic of "about you". The entries are tidied and bounded by the service;
 * this only makes sure they arrived as a list of strings of sane size.
 */
class AboutYouTopicDto {
  @IsArray()
  @ArrayMaxSize(32)
  @IsString({ each: true })
  @MaxLength(400, { each: true })
  entries!: string[];

  @IsString() @IsOptional() @IsIn(['EVERYONE', 'CONTACTS', 'NOBODY']) visibility?: string;
}

@Controller('api/v1/me')
@UseGuards(JwtAuthGuard)
export class UsersController {
  constructor(
    private readonly usersService: UsersService,
    private readonly aboutYou: AboutYouService,
  ) {}

  @Get()
  async getMe(@Req() req: { user: { sub: string } }) {
    return this.usersService.getMe(req.user.sub);
  }

  /** Is this Viro ID valid and free? With suggestions when it isn't (or when none is given). */
  @Get('viro-id/check')
  async checkViroId(@Req() req: { user: { sub: string } }, @Query('id') id?: string, @Query('name') name?: string) {
    return this.usersService.checkViroId(req.user.sub, id, name);
  }

  /** Someone else's profile, trimmed to what they allow me to see. */
  @Get("profile/:userId")
  async publicProfile(@Req() req: { user: { sub: string } }, @Param("userId") userId: string) {
    return this.usersService.publicProfile(req.user.sub, userId);
  }

  /**
   * Replace one topic — interests, strengths, weaknesses, fears, dreams or
   * goals. An empty list clears it.
   */
  @Put('about-you/:topic')
  async setAboutYou(
    @Req() req: { user: { sub: string } },
    @Param('topic') topic: string,
    @Body() body: AboutYouTopicDto,
  ) {
    return this.aboutYou.set(req.user.sub, topic, body.entries, body.visibility);
  }

  @Get('export')
  async exportMe(@Req() req: { user: { sub: string } }) {
    return this.usersService.exportMe(req.user.sub);
  }

  @Patch()
  async updateMe(@Req() req: { user: { sub: string } }, @Body() body: UpdateMeDto) {
    return this.usersService.updateMe(req.user.sub, body);
  }

  @Delete()
  async deleteMe(@Req() req: { user: { sub: string } }) {
    return this.usersService.deleteMe(req.user.sub);
  }

  @Post('avatar')
  @UseInterceptors(
    FileInterceptor('file', {
      storage: memoryStorage(),
      // The client downscales to ~1024px JPEG, which lands well under 1MB.
      // This is headroom for an unusually large one rather than a target: at
      // 2MB every unprocessed camera photo was rejected, which is exactly what
      // made avatar upload fail for everyone.
      limits: { fileSize: 8 * 1024 * 1024 },
    }),
  )
  async uploadAvatar(
    @Req() req: { user: { sub: string } },
    @UploadedFile() file: Express.Multer.File,
  ) {
    return this.usersService.uploadAvatar(req.user.sub, file);
  }
}
