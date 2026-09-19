import {
  Controller,
  Get,
  Patch,
  Delete,
  Post,
  Body,
  UseGuards,
  Req,
  UploadedFile,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { memoryStorage } from 'multer';
import { UsersService } from './users.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsOptional, IsIn } from 'class-validator';

class UpdateMeDto {
  @IsString()
  @IsOptional()
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
}

@Controller('api/v1/me')
@UseGuards(JwtAuthGuard)
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Get()
  async getMe(@Req() req: { user: { sub: string } }) {
    return this.usersService.getMe(req.user.sub);
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
