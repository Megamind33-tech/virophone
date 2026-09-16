import { Controller, Get, NotFoundException, Param, Res } from '@nestjs/common';
import { Response } from 'express';
import { readAvatarFile } from '../users/avatar.util';

@Controller('api/v1/media')
export class MediaController {
  @Get('avatars/:fileName')
  getAvatar(@Param('fileName') fileName: string, @Res() res: Response) {
    const file = readAvatarFile(fileName);
    if (!file) {
      throw new NotFoundException('Avatar not found');
    }
    res.setHeader('Content-Type', file.contentType);
    res.setHeader('Cache-Control', 'public, max-age=86400');
    res.send(file.buffer);
  }
}
