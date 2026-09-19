import {
  Controller,
  Get,
  Patch,
  Delete,
  Post,
  Body,
  UseGuards,
  Req,
  Query,
  UploadedFile,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { memoryStorage } from 'multer';
import { UsersService } from './users.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsOptional, IsIn, IsBoolean, MaxLength } from 'class-validator';

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
}

@Controller('api/v1/me')
@UseGuards(JwtAuthGuard)
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Get()
  async getMe(@Req() req: { user: { sub: string } }) {
    return this.usersService.getMe(req.user.sub);
  }

  /** Is this Viro ID valid and free? With suggestions when it isn't (or when none is given). */
  @Get('viro-id/check')
  async checkViroId(@Req() req: { user: { sub: string } }, @Query('id') id?: string, @Query('name') name?: string) {
    return this.usersService.checkViroId(req.user.sub, id, name);
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
