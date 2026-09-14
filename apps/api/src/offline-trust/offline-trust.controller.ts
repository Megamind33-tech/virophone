import { Controller, Get, UseGuards, Req } from '@nestjs/common';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { OfflineTrustService } from './offline-trust.service';

@Controller('api/v1/offline-trust')
@UseGuards(JwtAuthGuard)
export class OfflineTrustController {
  constructor(private readonly offlineTrustService: OfflineTrustService) {}

  @Get('material')
  async getMaterial(@Req() req: { user: { sub: string; deviceId: string } }) {
    const material = await this.offlineTrustService.getTrustMaterial(
      req.user.sub,
      req.user.deviceId,
    );
    return { material, syncedAt: new Date().toISOString() };
  }

  @Get('call-tickets')
  async getCallTickets(@Req() req: { user: { sub: string; deviceId: string } }) {
    const tickets = await this.offlineTrustService.issueOfflineCallTickets(
      req.user.sub,
      req.user.deviceId,
    );
    return { tickets, syncedAt: new Date().toISOString() };
  }
}
