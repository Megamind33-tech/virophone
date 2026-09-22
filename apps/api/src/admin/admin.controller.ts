import { Body, Controller, Delete, Get, Header, Param, ParseUUIDPipe, Patch, Post, Query, Res, UseGuards } from '@nestjs/common';
import { ArrayMaxSize, IsArray, IsIn, IsISO8601, IsOptional, IsString, IsUUID, MaxLength, MinLength } from 'class-validator';
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

const AUDIENCES = ['EVERYONE', 'SUBSCRIBERS', 'FREE'];
const STATUSES = ['DRAFT', 'SCHEDULED', 'ARCHIVED'];

class CampaignDto {
  @IsString() @MinLength(1) @MaxLength(80) name!: string;
  @IsOptional() @IsIn(AUDIENCES) audience?: string;
  @IsOptional() @IsISO8601() startsAt?: string;
  @IsOptional() @IsISO8601() endsAt?: string;
}

/** Only what was sent is changed; anything absent keeps its value. */
class CampaignPatchDto {
  @IsOptional() @IsString() @MinLength(1) @MaxLength(80) name?: string;
  @IsOptional() @IsIn(STATUSES) status?: string;
  @IsOptional() @IsIn(AUDIENCES) audience?: string;
  @IsOptional() @IsString() startsAt?: string;
  @IsOptional() @IsString() endsAt?: string;
}

class PromotionDto {
  @IsString() @MinLength(1) @MaxLength(80) title!: string;
  @IsOptional() @IsString() @MaxLength(240) body?: string;
  @IsOptional() @IsString() @MaxLength(120) action?: string;
}

/** The order a person dragged things into, as a list of ids. */
class ReorderDto {
  @IsArray() @ArrayMaxSize(100) @IsUUID('4', { each: true }) ids!: string[];
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

  // ------------------------------------------------------------ campaigns

  @Get('campaigns')
  campaigns() {
    return this.admin.campaigns();
  }

  @Get('campaigns/:id')
  campaign(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.campaign(id);
  }

  @Post('campaigns')
  createCampaign(@Body() body: CampaignDto) {
    return this.admin.createCampaign(body);
  }

  @Patch('campaigns/:id')
  updateCampaign(@Param('id', ParseUUIDPipe) id: string, @Body() body: CampaignPatchDto) {
    return this.admin.updateCampaign(id, body as Record<string, unknown>);
  }

  @Delete('campaigns/:id')
  deleteCampaign(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.deleteCampaign(id);
  }

  @Post('campaigns/:id/promotions')
  addPromotion(@Param('id', ParseUUIDPipe) id: string, @Body() body: PromotionDto) {
    return this.admin.addPromotion(id, body);
  }

  /** What a drag-and-drop leaves behind: the whole order, written at once. */
  @Patch('campaigns/:id/order')
  reorder(@Param('id', ParseUUIDPipe) id: string, @Body() body: ReorderDto) {
    return this.admin.reorderPromotions(id, body.ids);
  }

  @Patch('promotions/:id')
  updatePromotion(@Param('id', ParseUUIDPipe) id: string, @Body() body: PromotionDto) {
    return this.admin.updatePromotion(id, body);
  }

  @Delete('promotions/:id')
  deletePromotion(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.deletePromotion(id);
  }

  @Get('security-events')
  events(@Query('limit') limit?: string) {
    return this.admin.securityEvents(limit ? parseInt(limit, 10) : 50);
  }
}
