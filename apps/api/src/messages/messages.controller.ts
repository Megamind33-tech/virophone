import {
  Controller,
  Post,
  Get,
  Patch,
  Put,
  Delete,
  Param,
  Query,
  Body,
  UseGuards,
  Req,
  Res,
  UploadedFile,
  UseInterceptors,
  HttpStatus,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { memoryStorage } from 'multer';
import { Response } from 'express';
import {
  IsString,
  IsNotEmpty,
  IsOptional,
  MaxLength,
  IsBoolean,
  IsInt,
  IsIn,
  IsISO8601,
  Min,
  Max,
  ValidateIf,
} from 'class-validator';
import { MessagesService } from './messages.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { ALLOWED_IMAGE_MIME, ALLOWED_VOICE_MIME } from './media.store';
import { ViroException } from '../common/exceptions/viro.exception';

class SendMessageDto {
  @IsString() @IsOptional() toUserId?: string;
  @IsString() @IsOptional() conversationId?: string;
  @IsString() @IsOptional() @MaxLength(4000) body?: string;
  @IsString() @IsOptional() @MaxLength(64) clientMsgId?: string;
  @IsString() @IsOptional() @IsIn(['TEXT', 'VOICE', 'IMAGE', 'LOOP', 'text', 'voice', 'image', 'loop']) type?: string;
  @IsString() @IsOptional() replyToId?: string;
  @IsString() @IsOptional() mediaId?: string;
  @IsBoolean() @IsOptional() viewOnce?: boolean;
  @IsBoolean() @IsOptional() forwarded?: boolean;
  @IsISO8601() @IsOptional() deliverAt?: string;
  @IsString() @IsOptional() @MaxLength(24) effect?: string;
}

class EditMessageDto {
  @IsString() @IsNotEmpty() @MaxLength(4000) body!: string;
}

class ReactDto {
  @IsString() @IsNotEmpty() @MaxLength(32) emoji!: string;
}

class SettingsDto {
  @IsBoolean() @IsOptional() hidden?: boolean;
  @ValidateIf((_, v) => v !== null) @IsISO8601() @IsOptional() mutedUntil?: string | null;
  @ValidateIf((_, v) => v !== null) @IsInt() @IsOptional() disappearingSeconds?: number | null;
}

class PrivateSessionDto {
  @IsString() @IsNotEmpty() toUserId!: string;
  @IsInt() @Min(300) @Max(30 * 24 * 3600) durationSeconds!: number;
}

type AuthedReq = { user: { sub: string; deviceId: string } };

@Controller('api/v1/messages')
@UseGuards(JwtAuthGuard)
export class MessagesController {
  constructor(private readonly messagesService: MessagesService) {}

  @Post()
  async send(@Req() req: AuthedReq, @Body() body: SendMessageDto) {
    return this.messagesService.sendMessage(req.user.sub, req.user.deviceId, body);
  }

  /** Everything changed since the cursor — the catch-up that makes missed frames harmless. */
  @Get('sync')
  async sync(@Req() req: AuthedReq, @Query('since') since?: string) {
    return this.messagesService.sync(req.user.sub, since || undefined);
  }

  @Get('conversations')
  async conversations(@Req() req: AuthedReq) {
    return this.messagesService.listConversations(req.user.sub);
  }

  @Get('conversations/:id')
  async history(
    @Req() req: AuthedReq,
    @Param('id') id: string,
    @Query('limit') limit?: string,
    @Query('before') before?: string,
  ) {
    return this.messagesService.history(req.user.sub, id, limit ? parseInt(limit, 10) : 50, before);
  }

  @Get('conversations/:id/summary')
  async summary(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.conversationSummary(req.user.sub, id);
  }

  @Post('conversations/:id/read')
  async markRead(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.markRead(req.user.sub, id);
  }

  @Patch('conversations/:id/settings')
  async settings(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: SettingsDto) {
    return this.messagesService.updateSettings(req.user.sub, id, body);
  }

  /** Delete chat — for me only. */
  @Post('conversations/:id/clear')
  async clear(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.clearForMe(req.user.sub, id);
  }

  /** Reset — wipes the chat for both people, keeps the contact. */
  @Post('conversations/:id/reset')
  async reset(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.reset(req.user.sub, id);
  }

  @Post('conversations/:id/end-private')
  async endPrivate(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.endPrivate(req.user.sub, id);
  }

  @Post('private')
  async startPrivate(@Req() req: AuthedReq, @Body() body: PrivateSessionDto) {
    return this.messagesService.startPrivate(req.user.sub, body.toUserId, body.durationSeconds);
  }

  /** Erase & disconnect — every shared 1:1 conversation, for both people. */
  @Post('erase-with/:userId')
  async eraseWith(@Req() req: AuthedReq, @Param('userId') userId: string) {
    return this.messagesService.eraseWith(req.user.sub, userId);
  }

  @Patch(':id')
  async edit(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: EditMessageDto) {
    return this.messagesService.editMessage(req.user.sub, id, body.body);
  }

  @Delete(':id')
  async remove(@Req() req: AuthedReq, @Param('id') id: string, @Query('scope') scope?: string) {
    return this.messagesService.deleteMessage(req.user.sub, id, scope === 'everyone' ? 'everyone' : 'me');
  }

  @Put(':id/reaction')
  async react(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: ReactDto) {
    return this.messagesService.react(req.user.sub, id, body.emoji);
  }

  @Delete(':id/reaction')
  async unreact(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.react(req.user.sub, id, null);
  }

  @Post(':id/viewed')
  async viewed(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.markViewed(req.user.sub, id);
  }

  @Put(':id/star')
  async star(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.star(req.user.sub, id, true);
  }

  @Delete(':id/star')
  async unstar(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.star(req.user.sub, id, false);
  }

  @Put(':id/pin')
  async pin(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.pin(req.user.sub, id, true);
  }

  @Delete(':id/pin')
  async unpin(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.pin(req.user.sub, id, false);
  }

  /** Uploads a voice note; returns the media id to send with the message. */
  @Post('media')
  @UseInterceptors(
    FileInterceptor('file', {
      storage: memoryStorage(),
      // ~16-24 kbps audio: 5 MB is well over half an hour. Photos arrive
      // already downscaled by the app, well under this.
      limits: { fileSize: 5 * 1024 * 1024 },
    }),
  )
  async upload(
    @Req() req: AuthedReq,
    @UploadedFile() file: Express.Multer.File,
    @Body() body: { durationMs?: string; waveform?: string; width?: string; height?: string },
  ) {
    if (!file?.buffer?.length) {
      throw new ViroException('VALIDATION_ERROR', 'A file is required.', HttpStatus.BAD_REQUEST);
    }
    const isVoice = ALLOWED_VOICE_MIME.has(file.mimetype);
    if (!isVoice && !ALLOWED_IMAGE_MIME.has(file.mimetype)) {
      throw new ViroException('VALIDATION_ERROR', 'Unsupported file type.', HttpStatus.BAD_REQUEST);
    }
    const n = (v?: string) => (v ? parseInt(v, 10) : undefined);
    return this.messagesService.registerMedia(req.user.sub, file, {
      kind: isVoice ? 'VOICE' : 'IMAGE',
      durationMs: n(body.durationMs),
      waveform: body.waveform,
      width: n(body.width),
      height: n(body.height),
    });
  }

  @Get('media/:id')
  async download(@Req() req: AuthedReq, @Param('id') id: string, @Res() res: Response) {
    const file = await this.messagesService.mediaFor(req.user.sub, id);
    if (!file) {
      res.status(404).json({ code: 'NOT_FOUND', message: 'Recording not available.' });
      return;
    }
    res.setHeader('Content-Type', file.mime);
    res.setHeader('Cache-Control', 'private, max-age=604800');
    res.sendFile(file.path);
  }
}
