import { Controller, Get, Param, UseGuards, Req } from '@nestjs/common';
import { DirectoryService } from './directory.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

@Controller('api/v1/directory')
export class DirectoryController {
  constructor(private readonly directoryService: DirectoryService) {}

  @Get('exact/:viroId')
  @UseGuards(JwtAuthGuard)
  async exactLookup(
    @Req() req: { user: { sub: string } },
    @Param('viroId') viroId: string,
  ) {
    const result = await this.directoryService.exactLookup(req.user.sub, viroId);
    if (!result) {
      throw new ViroException('NOT_FOUND', 'User not found.', HttpStatus.NOT_FOUND);
    }
    return result;
  }
}
