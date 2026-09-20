import { Controller, Get, Header, Res } from '@nestjs/common';
import type { Response } from 'express';
import { VIRO_WEB_PAGE } from './viro-web.page';

/**
 * Viro on the web. Public, because linking is how you sign in here: the page
 * asks for a code and waits for a phone to approve it.
 */
@Controller('api/v1/web')
export class WebController {
  @Get()
  @Header('Cache-Control', 'no-cache')
  @Header('X-Frame-Options', 'DENY')
  page(@Res() res: Response) {
    res.type('html').send(VIRO_WEB_PAGE);
  }
}
