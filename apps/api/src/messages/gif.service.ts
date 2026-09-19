import { HttpStatus, Injectable, Logger } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';
import { ViroException } from '../common/exceptions/viro.exception';

export interface GifDto {
  id: string;
  url: string;
  previewUrl: string;
  width: number | null;
  height: number | null;
  provider: string;
}

export interface GifPage {
  items: GifDto[];
  next: string | null;
  provider: string;
}

/**
 * GIF search through the server, so the provider key never ships inside the
 * app. GIPHY_API_KEY or TENOR_API_KEY switches it on; without either the
 * endpoint says so plainly and the app hides GIF search (GIFs from the
 * gallery still work).
 */
@Injectable()
export class GifService {
  private readonly logger = new Logger('Gifs');

  constructor(private readonly redis: RedisService) {}

  get provider(): 'giphy' | 'tenor' | null {
    if (process.env.GIPHY_API_KEY) return 'giphy';
    if (process.env.TENOR_API_KEY) return 'tenor';
    return null;
  }

  async search(q: string | undefined, pos: string | undefined): Promise<GifPage> {
    const provider = this.provider;
    if (!provider) {
      throw new ViroException('SERVICE_UNAVAILABLE' as never, 'GIF search is not switched on yet.', HttpStatus.SERVICE_UNAVAILABLE);
    }
    const term = (q || '').trim().slice(0, 60);
    const key = `gif:${provider}:${term || '_trending'}:${pos || '0'}`;
    const cached = await this.redis.getJson<GifPage>(key).catch(() => null);
    if (cached) return cached;
    const page = provider === 'giphy' ? await this.giphy(term, pos) : await this.tenor(term, pos);
    await this.redis.setJson(key, page, 600).catch(() => undefined);
    return page;
  }

  private async giphy(term: string, pos?: string): Promise<GifPage> {
    const offset = Math.max(0, parseInt(pos || '0', 10) || 0);
    const base = term ? 'https://api.giphy.com/v1/gifs/search' : 'https://api.giphy.com/v1/gifs/trending';
    const u = new URL(base);
    u.searchParams.set('api_key', process.env.GIPHY_API_KEY!);
    u.searchParams.set('limit', '24');
    u.searchParams.set('offset', String(offset));
    u.searchParams.set('rating', 'pg-13');
    if (term) u.searchParams.set('q', term);
    const res = await fetch(u, { signal: AbortSignal.timeout(8000) });
    if (!res.ok) throw new ViroException('SERVICE_UNAVAILABLE' as never, 'GIF search failed.', HttpStatus.BAD_GATEWAY);
    const json = (await res.json()) as { data?: any[]; pagination?: { count?: number; offset?: number; total_count?: number } };
    const items = (json.data ?? []).map((g) => {
      const fw = g.images?.fixed_width ?? {};
      const small = g.images?.fixed_width_small ?? g.images?.preview_gif ?? fw;
      return {
        id: String(g.id),
        url: fw.url,
        previewUrl: small.url ?? fw.url,
        width: parseInt(fw.width, 10) || null,
        height: parseInt(fw.height, 10) || null,
        provider: 'giphy',
      } as GifDto;
    }).filter((g) => !!g.url);
    const p = json.pagination;
    const nextOffset = (p?.offset ?? offset) + (p?.count ?? items.length);
    return { items, next: p && nextOffset < (p.total_count ?? 0) ? String(nextOffset) : null, provider: 'giphy' };
  }

  private async tenor(term: string, pos?: string): Promise<GifPage> {
    const u = new URL(term ? 'https://tenor.googleapis.com/v2/search' : 'https://tenor.googleapis.com/v2/featured');
    u.searchParams.set('key', process.env.TENOR_API_KEY!);
    u.searchParams.set('client_key', 'viro');
    u.searchParams.set('limit', '24');
    u.searchParams.set('contentfilter', 'medium');
    u.searchParams.set('media_filter', 'gif,tinygif');
    if (term) u.searchParams.set('q', term);
    if (pos) u.searchParams.set('pos', pos);
    const res = await fetch(u, { signal: AbortSignal.timeout(8000) });
    if (!res.ok) throw new ViroException('SERVICE_UNAVAILABLE' as never, 'GIF search failed.', HttpStatus.BAD_GATEWAY);
    const json = (await res.json()) as { results?: any[]; next?: string };
    const items = (json.results ?? []).map((g) => {
      const gif = g.media_formats?.gif ?? {};
      const tiny = g.media_formats?.tinygif ?? gif;
      return {
        id: String(g.id),
        url: gif.url,
        previewUrl: tiny.url ?? gif.url,
        width: gif.dims?.[0] ?? null,
        height: gif.dims?.[1] ?? null,
        provider: 'tenor',
      } as GifDto;
    }).filter((g) => !!g.url);
    return { items, next: json.next || null, provider: 'tenor' };
  }
}
