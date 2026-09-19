import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { createHash } from 'crypto';
import { MediaObject } from '../database/entities/messaging-extras.entity';
import { RedisService } from '../redis/redis.service';
import { MediaStore } from './media.store';
import { safeFetch } from './safe-fetch';

export interface LinkPreviewDto {
  url: string;
  title: string | null;
  description: string | null;
  siteName: string | null;
  mediaId: string | null;
}

const IMAGE_MIME = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);

function decodeEntities(s: string): string {
  return s
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;|&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(parseInt(n, 10)))
    .replace(/\s+/g, ' ')
    .trim();
}

/** Reads <meta property="og:title" content="..."> in either attribute order. */
export function metaContent(html: string, names: string[]): string | null {
  for (const name of names) {
    const esc = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const a = new RegExp(`<meta[^>]+(?:property|name)=["']${esc}["'][^>]*content=["']([^"']*)["']`, 'i').exec(html);
    const b = new RegExp(`<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']${esc}["']`, 'i').exec(html);
    const v = (a ?? b)?.[1];
    if (v && v.trim()) return decodeEntities(v);
  }
  return null;
}

export function parsePreview(html: string, pageUrl: string) {
  const head = html.slice(0, 300_000);
  const title = metaContent(head, ['og:title', 'twitter:title']) ?? (/<title[^>]*>([^<]{1,300})<\/title>/i.exec(head)?.[1] ? decodeEntities(/<title[^>]*>([^<]{1,300})<\/title>/i.exec(head)![1]) : null);
  const description = metaContent(head, ['og:description', 'twitter:description', 'description']);
  const siteName = metaContent(head, ['og:site_name', 'application-name']) ?? new URL(pageUrl).hostname.replace(/^www\./, '');
  const image = metaContent(head, ['og:image:secure_url', 'og:image', 'twitter:image', 'twitter:image:src']);
  return {
    title: title?.slice(0, 200) ?? null,
    description: description?.slice(0, 400) ?? null,
    siteName: siteName?.slice(0, 80) ?? null,
    imageUrl: image ? new URL(image, pageUrl).toString() : null,
  };
}

/**
 * A card for a link: title, description, site and a thumbnail. Fetched by the
 * server on the sender's behalf and attached to the message, so the people
 * receiving it never contact the linked site just to see the card.
 */
@Injectable()
export class LinkPreviewService {
  private readonly logger = new Logger('LinkPreview');

  constructor(
    private readonly redis: RedisService,
    private readonly mediaStore: MediaStore,
    @InjectRepository(MediaObject) private readonly mediaRepo: Repository<MediaObject>,
  ) {}

  async preview(userId: string, rawUrl: string): Promise<LinkPreviewDto | null> {
    let url: URL;
    try {
      url = new URL(rawUrl.trim());
    } catch {
      return null;
    }
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    const key = `lp:${createHash('sha1').update(url.toString()).digest('hex')}`;
    const cached = await this.redis.getJson<LinkPreviewDto | { none: true }>(key).catch(() => null);
    if (cached) return 'none' in cached ? null : cached;

    try {
      const page = await safeFetch(url.toString(), { maxBytes: 600_000, accept: 'text/html,application/xhtml+xml' });
      if (!/html/i.test(page.contentType)) throw new Error('not html');
      const meta = parsePreview(page.body.toString('utf8'), page.finalUrl);
      if (!meta.title && !meta.description) throw new Error('no preview data');
      let mediaId: string | null = null;
      if (meta.imageUrl) {
        try {
          const img = await safeFetch(meta.imageUrl, { maxBytes: 1_500_000, accept: 'image/*', timeoutMs: 6000 });
          const mime = img.contentType.split(';')[0].trim().toLowerCase();
          if (IMAGE_MIME.has(mime) && img.body.length > 200 && img.body.length < 1_500_000) {
            const fileName = this.mediaStore.save(img.body, mime);
            const saved = await this.mediaRepo.save(
              this.mediaRepo.create({ ownerUserId: userId, kind: 'IMAGE', mime, sizeBytes: img.body.length, fileName }),
            );
            mediaId = saved.id;
          }
        } catch (e) {
          this.logger.debug(`thumbnail skipped: ${(e as Error).message}`);
        }
      }
      const result: LinkPreviewDto = {
        url: page.finalUrl,
        title: meta.title,
        description: meta.description,
        siteName: meta.siteName,
        mediaId,
      };
      await this.redis.setJson(key, result, 24 * 3600).catch(() => undefined);
      return result;
    } catch (e) {
      this.logger.debug(`preview failed for ${url.hostname}: ${(e as Error).message}`);
      await this.redis.setJson(key, { none: true }, 3600).catch(() => undefined);
      return null;
    }
  }
}
