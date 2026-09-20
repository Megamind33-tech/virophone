import { Body, Controller, Get, Param, Post, Query, Req, UseGuards } from '@nestjs/common';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { KeyBundleInput, KeysService, PrekeyInput } from './keys.service';

type Authed = { user: { sub: string; deviceId: string } };

/**
 * Public keys only. Everything here is safe for the server to hold and hand
 * out; nothing here can read a message.
 */
@Controller('api/v1/keys')
@UseGuards(JwtAuthGuard)
export class KeysController {
  constructor(private readonly keys: KeysService) {}

  /** This device publishes its identity, its signed prekey and a first batch. */
  @Post()
  async publish(@Req() req: Authed, @Body() body: KeyBundleInput) {
    return this.keys.publish(req.user.sub, req.user.deviceId, body);
  }

  /** How many one-time prekeys this device has left. */
  @Get('status')
  async status(@Req() req: Authed) {
    return this.keys.prekeyStatus(req.user.deviceId);
  }

  /** A top-up, when the phone sees it is running low. */
  @Post('prekeys')
  async topUp(@Req() req: Authed, @Body() body: { oneTimePreKeys?: PrekeyInput[] }) {
    return this.keys.addPrekeys(req.user.deviceId, body?.oneTimePreKeys ?? []);
  }

  /** Which devices this person can be reached on — asking costs no prekey. */
  @Get(':userId/devices')
  async devices(@Req() req: Authed, @Param('userId') userId: string) {
    return this.keys.devicesFor(req.user.sub, userId);
  }

  /**
   * What a sender needs to start talking to this person's devices. Each answer
   * spends one one-time prekey per device, so senders name the devices they
   * actually need with ?devices=id,id rather than asking about all of them.
   */
  @Get(':userId')
  async bundles(@Req() req: Authed, @Param('userId') userId: string, @Query('devices') devices?: string) {
    const only = (devices ?? '').split(',').map((d) => d.trim()).filter(Boolean);
    return this.keys.bundlesFor(req.user.sub, userId, only);
  }
}
