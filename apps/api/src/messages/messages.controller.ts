import {
  Controller,
  Post,
  Get,
  Param,
  Query,
  Body,
  UseGuards,
  Req,
} from '@nestjs/common';
import { IsString, IsNotEmpty, IsOptional, MaxLength } from 'class-validator';
import { MessagesService } from './messages.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';

class SendMessageDto {
  @IsString()
  @IsOptional()
  toUserId?: string;

  @IsString()
  @IsOptional()
  conversationId?: string;

  @IsString()
  @IsNotEmpty()
  @MaxLength(4000)
  body!: string;

  @IsString()
  @IsOptional()
  @MaxLength(64)
  clientMsgId?: string;
}

@Controller('api/v1/messages')
@UseGuards(JwtAuthGuard)
export class MessagesController {
  constructor(private readonly messagesService: MessagesService) {}

  @Post()
  async send(
    @Req() req: { user: { sub: string; deviceId: string } },
    @Body() body: SendMessageDto,
  ) {
    return this.messagesService.sendMessage(req.user.sub, req.user.deviceId, body);
  }

  @Get('conversations')
  async conversations(@Req() req: { user: { sub: string } }) {
    return this.messagesService.listConversations(req.user.sub);
  }

  @Get('conversations/:id')
  async history(
    @Req() req: { user: { sub: string } },
    @Param('id') id: string,
    @Query('limit') limit?: string,
  ) {
    return this.messagesService.history(
      req.user.sub,
      id,
      limit ? parseInt(limit, 10) : 50,
    );
  }

  @Post('conversations/:id/read')
  async markRead(
    @Req() req: { user: { sub: string } },
    @Param('id') id: string,
  ) {
    return this.messagesService.markRead(req.user.sub, id);
  }
}
