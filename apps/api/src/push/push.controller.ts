import { Controller, Post, Delete, Body, UseGuards, Req } from '@nestjs/common';
import { IsString, IsNotEmpty, IsOptional } from 'class-validator';
import { PushService } from './push.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';

class RegisterTokenDto {
  @IsString()
  @IsNotEmpty()
  token!: string;

  @IsString()
  @IsOptional()
  provider?: string;
}

class RemoveTokenDto {
  @IsString()
  @IsNotEmpty()
  token!: string;
}

@Controller('api/v1/push')
@UseGuards(JwtAuthGuard)
export class PushController {
  constructor(private readonly pushService: PushService) {}

  @Post('tokens')
  async register(
    @Req() req: { user: { sub: string; deviceId: string } },
    @Body() body: RegisterTokenDto,
  ) {
    await this.pushService.registerToken(
      req.user.sub,
      req.user.deviceId,
      body.token,
      body.provider || 'fcm',
    );
    return { registered: true };
  }

  @Delete('tokens')
  async remove(@Body() body: RemoveTokenDto) {
    await this.pushService.removeToken(body.token);
    return { removed: true };
  }
}
