import { Body, Controller, Delete, Get, Header, Param, Post, Query, Res, UseGuards } from '@nestjs/common';
import { IsOptional, IsString, IsUUID, MaxLength, MinLength } from 'class-validator';
import type { Response } from 'express';
import { ADMIN_CONSOLE_PAGE } from './admin.page';
import { AdminService } from './admin.service';
import { AdminGuard } from './admin.guard';

class NotifyDto {
  /** Absent means everybody with a device. */
  @IsOptional() @IsUUID() userId?: string;
  @IsString() @MinLength(1) @MaxLength(80) title!: string;
  @IsString() @MinLength(1) @MaxLength(240) body!: string;
}

/**
 * The console itself. Public HTML on purpose — it holds no data, and every
 * request it makes carries admin credentials the page asks for and keeps only
 * for the session. Guarding the page as well would mean a second login
 * mechanism for no extra safety.
 */
@Controller('api/v1/admin')
export class AdminConsoleController {
  @Get('console')
  @Header('Cache-Control', 'no-store')
  @Header('X-Frame-Options', 'DENY')
  @Header('Referrer-Policy', 'no-referrer')
  page(@Res() res: Response) {
    res.type('html').send(ADMIN_CONSOLE_PAGE);
  }
}

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

  @Get('overview')
  overview() {
    return this.admin.overview();
  }

  @Get('moments')
  moments(@Query('limit') limit?: string) {
    return this.admin.liveMoments(limit ? parseInt(limit, 10) : 100);
  }

  /** Ends a live Moment the way its host would, sweeper and all. */
  @Delete('moments/:id')
  endMoment(@Param('id') id: string) {
    return this.admin.endMoment(id);
  }

  @Get('subscriptions')
  subscriptions(@Query('limit') limit?: string) {
    return this.admin.subscriptions(limit ? parseInt(limit, 10) : 100);
  }

  @Get('plan-totals')
  planTotals() {
    return this.admin.planTotals();
  }

  @Post('notify')
  notify(@Body() body: NotifyDto) {
    return this.admin.notify({ ...body, by: 'admin-console' });
  }

  @Get('security-events')
  events(@Query('limit') limit?: string) {
    return this.admin.securityEvents(limit ? parseInt(limit, 10) : 50);
  }
}
