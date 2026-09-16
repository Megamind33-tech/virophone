import { Controller, Post, Get, Delete, Param, Body, UseGuards, Req } from '@nestjs/common';
import { BlocksService } from './blocks.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsString, IsNotEmpty } from 'class-validator';

class CreateBlockDto {
  @IsString()
  @IsNotEmpty()
  blockedUserId!: string;
}

@Controller('api/v1/blocks')
@UseGuards(JwtAuthGuard)
export class BlocksController {
  constructor(private readonly blocksService: BlocksService) {}

  @Get()
  async list(@Req() req: { user: { sub: string } }) {
    return this.blocksService.list(req.user.sub);
  }

  @Post()
  async block(@Req() req: { user: { sub: string } }, @Body() body: CreateBlockDto) {
    return this.blocksService.block(req.user.sub, body.blockedUserId);
  }

  @Delete(':userId')
  async unblock(@Req() req: { user: { sub: string } }, @Param('userId') userId: string) {
    return this.blocksService.unblock(req.user.sub, userId);
  }
}
