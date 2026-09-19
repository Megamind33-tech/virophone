import { Controller, Get, Param, Query, UseGuards, Req, Res, Header } from '@nestjs/common';
import type { Response } from 'express';
import { DirectoryService } from './directory.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import { normalizeViroId } from '../common/utils/viro-id.util';

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

  /** Find people: exact Viro ID or exact verified email. `{ person: null }` when nobody matches. */
  @Get('find')
  @UseGuards(JwtAuthGuard)
  async find(@Req() req: { user: { sub: string } }, @Query('q') q: string) {
    return { person: await this.directoryService.find(req.user.sub, q) };
  }
}

/**
 * The page behind a shared "Add me on Viro" link. Public, and deliberately
 * shows only the Viro ID from the link itself — it never looks the person up,
 * so the link can't be used to probe who is on Viro.
 */
@Controller('api/v1/invite')
export class InviteController {
  @Get(':viroId')
  @Header('Cache-Control', 'public, max-age=300')
  invitePage(@Param('viroId') raw: string, @Res() res: Response) {
    const id = normalizeViroId(raw);
    if (!id) {
      res.status(404).type('html').send(page('Link not valid', '<p>This Viro link isn\'t valid. Ask for a new one.</p>'));
      return;
    }
    const handle = `@${id}`;
    res.type('html').send(
      page(
        `Add ${handle} on Viro`,
        `<p class="id">${handle}</p>
         <a class="btn" href="viro://u/${id}">Open in Viro</a>
         <p class="hint">Already have Viro? Tap <b>Open in Viro</b>, or open the app, go to Contacts → Add people and search for <b>${handle}</b>.</p>`,
      ),
    );
  }
}

function page(title: string, body: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<style>
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0b1526;color:#eef3ff;
display:flex;min-height:100vh;align-items:center;justify-content:center;padding:16px;box-sizing:border-box}
main{max-width:360px;text-align:center}
h1{font-size:22px;margin:0 0 8px}.id{font-size:28px;font-weight:700;color:#5aa2ff;margin:8px 0 24px}
.btn{display:inline-block;background:#1565f5;color:#fff;text-decoration:none;padding:14px 28px;border-radius:28px;font-weight:600}
.hint{color:#9fb0cc;font-size:14px;line-height:1.5;margin-top:24px}
</style></head><body><main><h1>${title}</h1>${body}</main></body></html>`;
}
