import { Controller, Post, Delete, Param, Body, UseGuards, Req } from '@nestjs/common';
import { ConnectionsService } from './connections.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty } from 'class-validator';

class CreateConnectionDto {
  @IsString()
  @IsNotEmpty()
  targetUserId!: string;
}

@Controller('api/v1/connections')
@UseGuards(JwtAuthGuard)
export class ConnectionsController {
  constructor(private readonly connectionsService: ConnectionsService) {}

  @Post()
  async create(@Req() req: { user: { sub: string } }, @Body() body: CreateConnectionDto) {
    return this.connectionsService.create(req.user.sub, body.targetUserId);
  }

  @Post(':id/accept')
  async accept(@Req() req: { user: { sub: string } }, @Param('id') id: string) {
    return this.connectionsService.accept(id, req.user.sub);
  }

  @Post(':id/reject')
  async reject(@Req() req: { user: { sub: string } }, @Param('id') id: string) {
    return this.connectionsService.reject(id, req.user.sub);
  }

  @Delete(':id')
  async revoke(@Req() req: { user: { sub: string } }, @Param('id') id: string) {
    return this.connectionsService.revoke(id, req.user.sub);
  }
}
