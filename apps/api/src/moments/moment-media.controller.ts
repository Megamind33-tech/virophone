import { Controller, Get, Headers, Param, ParseUUIDPipe, Query, Res } from '@nestjs/common';
import { Response } from 'express';
import { MomentsService } from './moments.service';

/**
 * Where a phone's player fetches shared media from.
 *
 * Players fetch by address, without a login header, so the address carries a
 * short-lived signature for one person and one item — and every request is
 * still checked against the room as it is now. Someone who has left, been
 * separated by a block, or whose Moment has ended gets nothing, whatever
 * address they hold. Byte ranges are served so a film can be seeked without
 * downloading it all.
 */
@Controller('api/v1/moment-media')
export class MomentMediaController {
  constructor(private readonly moments: MomentsService) {}

  @Get(':mediaId')
  async stream(
    @Param('mediaId', ParseUUIDPipe) mediaId: string,
    @Query('u') userId: string,
    @Query('e') expires: string,
    @Query('s') signature: string,
    @Headers('range') range: string | undefined,
    @Res() res: Response,
  ) {
    const opened = await this.moments.openStream(mediaId, String(userId ?? ''), String(expires ?? ''), String(signature ?? ''), range);
    if (opened === 'gone') {
      res.status(404).json({ code: 'NOT_FOUND', message: 'That is no longer here to play.' });
      return;
    }
    if (opened === 'unsatisfiable') {
      res.status(416).end();
      return;
    }
    const { range: r, partial } = opened;
    res.status(partial ? 206 : 200);
    res.setHeader('Content-Type', r.mime);
    res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('Content-Length', String(r.end - r.start + 1));
    if (partial) res.setHeader('Content-Range', `bytes ${r.start}-${r.end}/${r.size}`);
    // Only this person, only for now: never cached anywhere shared.
    res.setHeader('Cache-Control', 'private, no-store');
    r.stream.on('error', () => res.destroy());
    res.on('close', () => r.stream.destroy());
    r.stream.pipe(res);
  }
}
