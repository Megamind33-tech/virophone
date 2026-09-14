import { Controller, Get, Patch, Body, UseGuards, Req } from '@nestjs/common';
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

  @Patch()
  async updateMe(@Req() req: { user: { sub: string } }, @Body() body: UpdateMeDto) {
    return this.usersService.updateMe(req.user.sub, body);
  }
}
