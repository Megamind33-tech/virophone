import {
  Controller,
  Delete,
  Get,
  HttpStatus,
  Post,
  Req,
  Res,
  UploadedFile,
  UseGuards,
  UseInterceptors,
  Body,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { memoryStorage } from 'multer';
import { Response } from 'express';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { BackupService, MAX_BACKUP_BYTES } from './backup.service';
import { ViroException } from '../common/exceptions/viro.exception';

type AuthedReq = { user: { sub: string; deviceId: string } };

/**
 * A person's encrypted backup of their own chats.
 *
 * Everything crossing this boundary is ciphertext. The server can say how big
 * it is and when it arrived, and hand it back to the phone that asks — and
 * that is the whole of what it can do with it.
 */
@Controller('api/v1/backup')
@UseGuards(JwtAuthGuard)
export class BackupController {
  constructor(private readonly backups: BackupService) {}

  /** Last backed up, how big, how many messages. */
  @Get()
  async status(@Req() req: AuthedReq) {
    return this.backups.status(req.user.sub);
  }

  @Post()
  @UseInterceptors(
    FileInterceptor('file', { storage: memoryStorage(), limits: { fileSize: MAX_BACKUP_BYTES } }),
  )
  async upload(
    @Req() req: AuthedReq,
    @UploadedFile() file: Express.Multer.File,
    @Body() body: { messageCount?: string; conversationCount?: string; version?: string },
  ) {
    if (!file?.buffer?.length) {
      throw new ViroException('VALIDATION_ERROR', 'A backup is required.', HttpStatus.BAD_REQUEST);
    }
    const n = (v?: string) => (v ? parseInt(v, 10) : undefined);
    return this.backups.store(req.user.sub, req.user.deviceId ?? null, file.buffer, {
      messageCount: n(body.messageCount),
      conversationCount: n(body.conversationCount),
      version: n(body.version),
    });
  }

  /** The archive, for a phone that has the recovery key. */
  @Get('archive')
  async archive(@Req() req: AuthedReq, @Res() res: Response) {
    const found = await this.backups.archive(req.user.sub);
    if (!found) throw new ViroException('NOT_FOUND', 'There is no backup.', HttpStatus.NOT_FOUND);
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Length', String(found.sizeBytes));
    res.send(found.data);
  }

  @Delete()
  async remove(@Req() req: AuthedReq) {
    return this.backups.remove(req.user.sub);
  }
}
