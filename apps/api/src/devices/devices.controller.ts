import { Controller, Get, Post, Delete, Param, Query, Body, UseGuards, Req } from '@nestjs/common';
import { DevicesService } from './devices.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty, MaxLength } from 'class-validator';
import { DeviceLinkService } from './device-link.service';

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

class ApproveLinkDto {
  @IsString()
  @IsNotEmpty()
  @MaxLength(16)
  code!: string;
}

/** Starting a link, and waiting for it, happen before anyone is signed in. */
@Controller('api/v1/devices/link')
export class DeviceLinkController {
  constructor(private readonly links: DeviceLinkService) {}

  /** The browser asks for a code to show. */
  @Post('start')
  async start(@Body() body: { label?: string }) {
    return this.links.start(body?.label);
  }

  /** The browser waits here until the phone approves. */
  @Get(':linkId')
  async poll(@Param('linkId') linkId: string, @Query('secret') secret: string) {
    return this.links.poll(linkId, secret || '');
  }

  /** The phone approves, by typing the code the browser shows. */
  @Post('approve')
  @UseGuards(JwtAuthGuard)
  async approve(@Req() req: { user: { sub: string } }, @Body() body: ApproveLinkDto) {
    return this.links.approve(req.user.sub, body.code);
  }
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
