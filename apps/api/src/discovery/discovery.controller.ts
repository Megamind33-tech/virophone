import { Controller, Post, Get, Body, Query, UseGuards, Req } from '@nestjs/common';
import { DiscoveryService } from './discovery.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty, IsArray, IsOptional } from 'class-validator';

class RegisterEphemeralDto {
  @IsString()
  @IsNotEmpty()
  ephemeralId!: string;
}

class ResolveEphemeralDto {
  @IsString()
  @IsNotEmpty()
  ephemeralId!: string;

  @IsArray()
  @IsString({ each: true })
  authorizedUserIds!: string[];
}

@Controller('api/v1/discovery')
@UseGuards(JwtAuthGuard)
export class DiscoveryController {
  constructor(private readonly discoveryService: DiscoveryService) {}

  @Post('ephemeral')
  async registerEphemeral(
    @Req() req: { user: { sub: string; deviceId: string } },
    @Body() body: RegisterEphemeralDto,
  ) {
    return this.discoveryService.registerEphemeral(
      req.user.sub,
      req.user.deviceId,
      body.ephemeralId,
    );
  }

  @Post('ephemeral/resolve')
  async resolveEphemeral(
    @Req() req: { user: { sub: string } },
    @Body() body: ResolveEphemeralDto,
  ) {
    const result = await this.discoveryService.resolveEphemeral(
      req.user.sub,
      body.ephemeralId,
      body.authorizedUserIds,
    );
    if (!result.authorized) {
      return { authorized: false };
    }
    return result;
  }
}
