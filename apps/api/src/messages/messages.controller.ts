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
  IsNumber,
  Min,
  Max,
  ValidateIf,
} from 'class-validator';
import { MessagesService } from './messages.service';
import { LinkPreviewService } from './link-preview.service';
import { GifService } from './gif.service';
import { TranscriptionService } from './transcription.service';
import { IsArray, IsObject, ArrayMaxSize } from 'class-validator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { ALLOWED_IMAGE_MIME, ALLOWED_VOICE_MIME, ALLOWED_FILE_MIME } from './media.store';
import { ViroException } from '../common/exceptions/viro.exception';

class SendMessageDto {
  @IsString() @IsOptional() toUserId?: string;
  @IsString() @IsOptional() conversationId?: string;
  @IsString() @IsOptional() @MaxLength(4000) body?: string;
  @IsString() @IsOptional() @MaxLength(64) clientMsgId?: string;
  @IsString() @IsOptional() @IsIn([
    'TEXT', 'VOICE', 'IMAGE', 'LOOP', 'POLL', 'GIF', 'STICKER', 'FILE', 'CONTACT', 'LOCATION',
    'text', 'voice', 'image', 'loop', 'poll', 'gif', 'sticker', 'file', 'contact', 'location',
  ]) type?: string;
  @IsString() @IsOptional() replyToId?: string;
  @IsString() @IsOptional() mediaId?: string;
  @IsBoolean() @IsOptional() viewOnce?: boolean;
  @IsBoolean() @IsOptional() forwarded?: boolean;
  @IsISO8601() @IsOptional() deliverAt?: string;
  @IsString() @IsOptional() @MaxLength(24) effect?: string;
  @IsObject() @IsOptional() poll?: { question: string; options: string[]; multi?: boolean };
  @IsObject() @IsOptional() linkPreview?: { url: string; title?: string; description?: string; siteName?: string; mediaId?: string };
  @IsObject() @IsOptional() gif?: { url: string; previewUrl?: string; width?: number; height?: number; provider?: string };
  @IsObject() @IsOptional() sticker?: { pack: string; id: string };
  @IsObject() @IsOptional() contact?: { name: string; phones?: string[]; viroId?: string; userId?: string };
  @IsObject() @IsOptional() location?: { lat: number; lng: number; accuracy?: number; label?: string; liveSeconds?: number };
  @IsArray() @IsOptional() @ArrayMaxSize(64) mentions?: string[];
  /**
   * One sealed copy per recipient device. When these are present the server
   * stores nothing else: no body, no metadata, nothing it could read.
   */
  @IsArray() @IsOptional() @ArrayMaxSize(512)
  envelopes?: { deviceId: string; ciphertext: string; type?: number }[];
  /**
   * For a sealed live location: how long the share runs. Where the person is
   * goes inside the ciphertext — this says only when to stop carrying updates.
   */
  @IsInt() @IsOptional() liveSeconds?: number;
  /**
   * Carried like a message but not one — an encrypted reaction. No push, no
   * unread badge, and it never becomes the line shown in the inbox.
   */
  @IsBoolean() @IsOptional() silent?: boolean;
}

class GroupDto {
  @IsString() @IsNotEmpty() @MaxLength(120) title!: string;
  @IsArray() @ArrayMaxSize(255) memberIds!: string[];
  @IsString() @IsOptional() @MaxLength(300) description?: string;
}

class GroupPatchDto {
  @IsString() @IsOptional() @MaxLength(120) title?: string;
  @ValidateIf((_, v) => v !== null) @IsString() @IsOptional() @MaxLength(300) description?: string | null;
}

class MembersDto {
  @IsArray() @ArrayMaxSize(255) userIds!: string[];
}

class RoleDto {
  @IsString() @IsIn(['ADMIN', 'MEMBER']) role!: 'ADMIN' | 'MEMBER';
}

class LocationPointDto {
  @IsNumber() @Min(-90) @Max(90) lat!: number;
  @IsNumber() @Min(-180) @Max(180) lng!: number;
  @IsNumber() @IsOptional() @Min(0) accuracy?: number;
}

/**
 * Either a position, or — when the share is encrypted — sealed copies of it,
 * one per device. Never both: a sealed share has no coordinates to send.
 */
class LiveLocationUpdateDto {
  @IsNumber() @IsOptional() @Min(-90) @Max(90) lat?: number;
  @IsNumber() @IsOptional() @Min(-180) @Max(180) lng?: number;
  @IsNumber() @IsOptional() @Min(0) accuracy?: number;
  @IsArray() @IsOptional() @ArrayMaxSize(512)
  envelopes?: { deviceId: string; ciphertext: string; type?: number }[];
}

class VoteDto {
  @IsArray() @ArrayMaxSize(12) options!: number[];
}

class EditMessageDto {
  // Empty for an encrypted edit: the new words are inside the envelopes.
  @IsString() @IsOptional() @MaxLength(4000) body?: string;
  /** Sealed copies of the edited message, one per device. */
  @IsArray() @IsOptional() @ArrayMaxSize(512)
  envelopes?: { deviceId: string; ciphertext: string; type?: number }[];
}

class ReactDto {
  @IsString() @IsNotEmpty() @MaxLength(32) emoji!: string;
}

class SettingsDto {
  @IsBoolean() @IsOptional() hidden?: boolean;
  @ValidateIf((_, v) => v !== null) @IsISO8601() @IsOptional() mutedUntil?: string | null;
  @ValidateIf((_, v) => v !== null) @IsInt() @IsOptional() disappearingSeconds?: number | null;
  // Clients whose JSON encoders drop nulls say "off" explicitly.
  @IsBoolean() @IsOptional() clearDisappearing?: boolean;
  @IsBoolean() @IsOptional() clearMute?: boolean;
  @IsBoolean() @IsOptional() archived?: boolean;
  @IsBoolean() @IsOptional() pinned?: boolean;
  @IsBoolean() @IsOptional() markUnread?: boolean;
}

class PrivateSessionDto {
  @IsString() @IsNotEmpty() toUserId!: string;
  @IsInt() @Min(300) @Max(30 * 24 * 3600) durationSeconds!: number;
}

type AuthedReq = { user: { sub: string; deviceId: string } };

/** Voice notes and photos stay small; a document may be up to 25 MB. */
const MAX_MEDIA_BYTES = 5 * 1024 * 1024;
const MAX_FILE_BYTES = 25 * 1024 * 1024;

/** A document keeps the sender's file name — minus any path, and length-capped. */
function cleanFileName(name?: string): string | undefined {
  const base = (name || '').split(/[\\/]/).pop()?.trim();
  if (!base) return undefined;
  return base.replace(/[\u0000-\u001f]/g, '').slice(0, 255) || undefined;
}

@Controller('api/v1/messages')
@UseGuards(JwtAuthGuard)
export class MessagesController {
  constructor(
    private readonly messagesService: MessagesService,
    private readonly linkPreviews: LinkPreviewService,
    private readonly gifs: GifService,
    private readonly transcripts: TranscriptionService,
  ) {}

  /** What this server can do, so the app shows only what works. */
  @Get('features')
  features() {
    return {
      gifs: this.gifs.provider !== null,
      gifProvider: this.gifs.provider,
      transcripts: this.transcripts.enabled,
      /**
       * Whether phones should start encrypting one-to-one chats.
       *
       * Off until files, voice notes and polls can be sealed too: a chat that
       * has gone encrypted refuses anything the server would have to store in
       * the clear, so turning this on early would stop photos working in that
       * chat. The keys, sessions and envelopes all work — this switch decides
       * when they are used on real conversations.
       */
      e2ee: process.env.E2EE_ENABLED === 'true',
    };
  }

  @Get('search')
  async search(@Req() req: AuthedReq, @Query('q') q?: string, @Query('conversationId') conversationId?: string) {
    return this.messagesService.search(req.user.sub, q || '', conversationId || undefined);
  }

  @Get('link-preview')
  async linkPreview(@Req() req: AuthedReq, @Query('url') url?: string) {
    return { preview: url ? await this.linkPreviews.preview(req.user.sub, url) : null };
  }

  @Get('gifs')
  async searchGifs(@Query('q') q?: string, @Query('pos') pos?: string) {
    return this.gifs.search(q, pos);
  }

  @Post('media/:id/transcribe')
  async transcribe(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.transcripts.transcribe(req.user.sub, id);
  }

  // --- groups
  @Post('groups')
  async createGroup(@Req() req: AuthedReq, @Body() body: GroupDto) {
    return this.messagesService.createGroup(req.user.sub, body.title, body.memberIds, body.description);
  }

  @Get('conversations/:id/members')
  async members(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.members(req.user.sub, id);
  }

  @Post('conversations/:id/members')
  async addMembers(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: MembersDto) {
    return this.messagesService.addMembers(req.user.sub, id, body.userIds);
  }

  @Delete('conversations/:id/members/:userId')
  async removeMember(@Req() req: AuthedReq, @Param('id') id: string, @Param('userId') userId: string) {
    return this.messagesService.removeMember(req.user.sub, id, userId);
  }

  @Put('conversations/:id/members/:userId/role')
  async setRole(@Req() req: AuthedReq, @Param('id') id: string, @Param('userId') userId: string, @Body() body: RoleDto) {
    return this.messagesService.setRole(req.user.sub, id, userId, body.role);
  }

  @Post('conversations/:id/leave')
  async leave(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.leaveGroup(req.user.sub, id);
  }

  /** The group's shareable link (admins only; made on first use). */
  @Get('conversations/:id/invite')
  async groupInvite(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.groupInvite(req.user.sub, id);
  }

  /** A new link; the old one stops working. */
  @Post('conversations/:id/invite/reset')
  async resetGroupInvite(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.resetGroupInvite(req.user.sub, id);
  }

  /** No link at all until an admin makes another. */
  @Delete('conversations/:id/invite')
  async revokeGroupInvite(@Req() req: AuthedReq, @Param('id') id: string) {
    return this.messagesService.revokeGroupInvite(req.user.sub, id);
  }

  /** What a link shows before you decide to join. */
  @Get('groups/invite/:code')
  async invitePreview(@Req() req: AuthedReq, @Param('code') code: string) {
    return this.messagesService.groupInvitePreview(req.user.sub, code);
  }

  @Post('groups/invite/:code/join')
  async joinByInvite(@Req() req: AuthedReq, @Param('code') code: string) {
    return this.messagesService.joinGroupByInvite(req.user.sub, code);
  }

  @Patch('conversations/:id/group')
  async updateGroup(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: GroupPatchDto) {
    return this.messagesService.updateGroup(req.user.sub, id, body);
  }

  /**
   * Moves a live location on — its sender only, while the share is running.
   *
   * An encrypted share sends sealed copies instead of coordinates: the server
   * swaps what each device is holding without ever seeing a position.
   */
  @Put(':id/location')
  async updateLocation(@Req() req: AuthedReq, @Param("id") id: string, @Body() body: LiveLocationUpdateDto) {
    if (body?.envelopes?.length) {
      return this.messagesService.updateSealedLiveLocation(req.user.sub, req.user.deviceId ?? null, id, body.envelopes);
    }
    return this.messagesService.updateLiveLocation(req.user.sub, id, body as LocationPointDto);
  }

  /** Ends a live location share early. */
  @Post(':id/location/stop')
  async stopLocation(@Req() req: AuthedReq, @Param("id") id: string) {
    return this.messagesService.stopLiveLocation(req.user.sub, id);
  }

  @Put(':id/vote')
  async vote(@Req() req: AuthedReq, @Param('id') id: string, @Body() body: VoteDto) {
    return this.messagesService.vote(req.user.sub, id, body.options);
  }

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
    const { clearDisappearing, clearMute, ...rest } = body;
    if (clearDisappearing) rest.disappearingSeconds = null;
    if (clearMute) rest.mutedUntil = null;
    return this.messagesService.updateSettings(req.user.sub, id, rest);
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
    return this.messagesService.editMessage(req.user.sub, id, body.body ?? '', body.envelopes);
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
      // Documents are the large case (25 MB); voice and photos are held to
      // 5 MB below. ~16-24 kbps audio: 5 MB is well over half an hour, and
      // photos arrive already downscaled by the app.
      limits: { fileSize: MAX_FILE_BYTES },
    }),
  )
  async upload(
    @Req() req: AuthedReq,
    @UploadedFile() file: Express.Multer.File,
    @Body() body: {
      durationMs?: string;
      waveform?: string;
      width?: string;
      height?: string;
      kind?: string;
      fileName?: string;
      /** "1" when the bytes are end-to-end encrypted. */
      sealed?: string;
    },
  ) {
    if (!file?.buffer?.length) {
      throw new ViroException('VALIDATION_ERROR', 'A file is required.', HttpStatus.BAD_REQUEST);
    }
    // An end-to-end encrypted file arrives as ciphertext: there is no mime
    // type to check, because there is nothing here to recognise. What kind of
    // thing it is, what it was called and how long it plays travel inside the
    // message. All the server enforces is how big it may be.
    const sealed = String(body.sealed ?? '') === '1' || String(body.sealed ?? '') === 'true';
    if (sealed) {
      const kind = String(body.kind || '').toUpperCase();
      if (!['VOICE', 'IMAGE', 'FILE'].includes(kind)) {
        throw new ViroException('VALIDATION_ERROR', 'Unsupported file type.', HttpStatus.BAD_REQUEST);
      }
      if (kind !== 'FILE' && file.size > MAX_MEDIA_BYTES) {
        throw new ViroException('VALIDATION_ERROR', 'That file is too large.', HttpStatus.BAD_REQUEST);
      }
      return this.messagesService.registerMedia(req.user.sub, file, {
        kind: kind as 'VOICE' | 'IMAGE' | 'FILE',
        sealed: true,
      });
    }
    const isVoice = ALLOWED_VOICE_MIME.has(file.mimetype);
    const isImage = !isVoice && ALLOWED_IMAGE_MIME.has(file.mimetype);
    // "document" means the sender picked a file rather than a photo or a recording.
    const isFile = !isVoice && !isImage && String(body.kind || '').toUpperCase() === 'FILE';
    if (isFile && !ALLOWED_FILE_MIME.has(file.mimetype)) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'That kind of file can\'t be sent on Viro.',
        HttpStatus.BAD_REQUEST,
      );
    }
    if (!isVoice && !isImage && !isFile) {
      throw new ViroException('VALIDATION_ERROR', 'Unsupported file type.', HttpStatus.BAD_REQUEST);
    }
    if (!isFile && file.size > MAX_MEDIA_BYTES) {
      throw new ViroException('VALIDATION_ERROR', 'That file is too large.', HttpStatus.BAD_REQUEST);
    }
    const n = (v?: string) => (v ? parseInt(v, 10) : undefined);
    return this.messagesService.registerMedia(req.user.sub, file, {
      kind: isVoice ? 'VOICE' : isImage ? 'IMAGE' : 'FILE',
      originalName: cleanFileName(body.fileName) ?? cleanFileName(file.originalname),
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
    if (file.originalName) {
      // Saved under the name the sender gave it. Quotes and control characters
      // are stripped so the name can't break out of the header.
      const safe = file.originalName.replace(/["\r\n]/g, '');
      res.setHeader('Content-Disposition', `attachment; filename="${safe}"; filename*=UTF-8''${encodeURIComponent(file.originalName)}`);
    }
    res.send(file.data);
  }
}
