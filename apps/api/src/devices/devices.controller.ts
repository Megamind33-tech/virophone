import { Controller, Get, Post, Delete, Param, Body, UseGuards, Req } from '@nestjs/common';
import { DevicesService } from './devices.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty } from 'class-validator';

class RegisterDeviceDto {
  @IsString()
  @IsNotEmpty()
  publicKey!: string;

  @IsString()
  @IsNotEmpty()
  platform!: string;

  @IsString()
  @IsNotEmpty()
  appVersion!: string;
}

@Controller('api/v1/devices')
@UseGuards(JwtAuthGuard)
export class DevicesController {
  constructor(private readonly devicesService: DevicesService) {}

  @Post('register')
  async register(@Req() req: { user: { sub: string } }, @Body() body: RegisterDeviceDto) {
    return this.devicesService.register(req.user.sub, body.publicKey, body.platform, body.appVersion);
  }

  @Get()
  async list(@Req() req: { user: { sub: string } }) {
    return this.devicesService.list(req.user.sub);
  }

  @Delete(':id')
  async revoke(@Req() req: { user: { sub: string } }, @Param('id') id: string) {
    return this.devicesService.revoke(req.user.sub, id);
  }
}
