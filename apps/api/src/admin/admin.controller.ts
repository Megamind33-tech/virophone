import { Controller, Get, Post, Param, Query, UseGuards } from '@nestjs/common';
import { AdminService } from './admin.service';
import { AdminGuard } from './admin.guard';

@Controller('api/v1/admin')
@UseGuards(AdminGuard)
export class AdminController {
  constructor(private readonly admin: AdminService) {}

  @Get('users')
  listUsers(@Query('q') q?: string, @Query('limit') limit?: string) {
    return this.admin.listUsers(q, limit ? parseInt(limit, 10) : 50);
  }

  @Get('users/:id')
  getUser(@Param('id') id: string) {
    return this.admin.getUser(id);
  }

  @Post('users/:id/suspend')
  suspend(@Param('id') id: string) {
    return this.admin.setStatus(id, 'SUSPENDED');
  }

  @Post('users/:id/unsuspend')
  unsuspend(@Param('id') id: string) {
    return this.admin.setStatus(id, 'ACTIVE');
  }

  @Get('security-events')
  events(@Query('limit') limit?: string) {
    return this.admin.securityEvents(limit ? parseInt(limit, 10) : 50);
  }
}
