import { Body, Controller, Get, Post, Req, UseGuards } from '@nestjs/common';
import { IsNotEmpty, IsString } from 'class-validator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { SubscriptionsService } from './subscriptions.service';

class SelectPlanDto {
  @IsString()
  @IsNotEmpty()
  planId!: string;
}

@Controller('api/v1')
export class SubscriptionsController {
  constructor(private readonly subscriptionsService: SubscriptionsService) {}

  @Get('plans')
  async listPlans() {
    return this.subscriptionsService.listPlans();
  }

  @Get('me/subscription')
  @UseGuards(JwtAuthGuard)
  async mine(@Req() req: { user: { sub: string } }) {
    return this.subscriptionsService.getMine(req.user.sub);
  }

  @Post('me/subscription')
  @UseGuards(JwtAuthGuard)
  async select(
    @Req() req: { user: { sub: string } },
    @Body() body: SelectPlanDto,
  ) {
    return this.subscriptionsService.selectPlan(req.user.sub, body.planId);
  }
}
