import { Controller, Get, Req, UseGuards } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';

type Authed = { user: { sub: string } };

/**
 * What a campaign is saying right now, for the app to read.
 *
 * Separate from the admin routes on purpose: this is the only campaign surface
 * an ordinary signed-in person can reach, it never exposes drafts, archived
 * campaigns, periods or anything about how a campaign was arranged, and it
 * answers with an empty list far more often than not.
 *
 * Nothing in the app is obliged to render this. It exists so that placement is
 * a product decision somebody makes deliberately, rather than a banner
 * appearing in the middle of Now because the endpoint happened to be there.
 */
@Controller('api/v1/promotions')
@UseGuards(JwtAuthGuard)
export class CampaignsController {
  constructor(private readonly db: DataSource) {}

  @Get('live')
  async live(@Req() req: Authed) {
    // Running is worked out here from the clock, the same way the console
    // shows it, so a finished campaign cannot keep being served by a row that
    // still says it is on.
    const rows = await this.db.query(
      `SELECT p.id, p.title, p.body, p.action, c.id AS campaign_id, c.name AS campaign
       FROM promotions p
       JOIN campaigns c ON c.id = p.campaign_id
       WHERE c.status = 'SCHEDULED'
         AND (c.starts_at IS NULL OR c.starts_at <= now())
         AND (c.ends_at IS NULL OR c.ends_at > now())
         AND (
           c.audience = 'EVERYONE'
           OR (c.audience = 'SUBSCRIBERS' AND EXISTS (
                 SELECT 1 FROM subscriptions s
                 WHERE s.user_id = $1 AND s.status = 'ACTIVE'))
           OR (c.audience = 'FREE' AND NOT EXISTS (
                 SELECT 1 FROM subscriptions s
                 WHERE s.user_id = $1 AND s.status = 'ACTIVE'))
         )
       ORDER BY c.starts_at DESC NULLS LAST, p.position, p.created_at
       LIMIT 20`,
      [req.user.sub],
    );
    return {
      promotions: rows.map((r: Record<string, string>) => ({
        id: r.id,
        campaignId: r.campaign_id,
        campaign: r.campaign,
        title: r.title,
        body: r.body ?? null,
        action: r.action ?? null,
      })),
    };
  }
}
