import { HttpStatus, Injectable } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { ViroException } from '../common/exceptions/viro.exception';
import { VisibilityService } from './visibility.service';
import {
  cleanEntries,
  defaultVisibility,
  isTopic,
  isVisibility,
  Topic,
  TOPICS,
  Visibility,
  visibilityProblem,
} from './about-you';

export interface TopicAnswer {
  topic: Topic;
  entries: string[];
  visibility: Visibility;
}

/**
 * What somebody has said about themselves, and who may read it.
 *
 * Every read on behalf of somebody else goes through VisibilityService, the
 * same gate as the photo, the about line and last seen, and a block in either
 * direction hides everything — the caller is expected to have checked that
 * before asking, exactly as publicProfile already does.
 */
@Injectable()
export class AboutYouService {
  constructor(
    private readonly db: DataSource,
    private readonly visibility: VisibilityService,
  ) {}

  /**
   * Everything, for the person themselves: every topic, answered or not, so a
   * screen can show where they have not said anything yet — and which way
   * each one is set before they have touched it.
   */
  async mine(userId: string): Promise<TopicAnswer[]> {
    const rows: { topic: string; entries: string[]; visibility: string }[] = await this.db.query(
      `SELECT topic, entries, visibility FROM profile_topics WHERE user_id = $1`,
      [userId],
    );
    const byTopic = new Map(rows.map((r) => [r.topic, r]));
    return TOPICS.map((topic) => {
      const row = byTopic.get(topic);
      return {
        topic,
        entries: row?.entries ?? [],
        visibility: (row && isVisibility(row.visibility) ? row.visibility : defaultVisibility(topic)),
      };
    });
  }

  /**
   * Replace one topic. An empty list removes it — saying nothing is an
   * answer, and it is stored as nothing rather than as an empty row somebody
   * might later mistake for a choice.
   */
  async set(userId: string, rawTopic: string, rawEntries: unknown, rawVisibility?: string): Promise<TopicAnswer> {
    const topic = String(rawTopic).toUpperCase();
    if (!isTopic(topic)) {
      throw new ViroException('VALIDATION_ERROR', 'That is not something you can add here.', HttpStatus.BAD_REQUEST);
    }
    const entries = cleanEntries(rawEntries);

    let visibility: Visibility;
    if (rawVisibility === undefined || rawVisibility === null) {
      const [existing] = await this.db.query(
        `SELECT visibility FROM profile_topics WHERE user_id = $1 AND topic = $2`,
        [userId, topic],
      );
      visibility = existing && isVisibility(existing.visibility) ? existing.visibility : defaultVisibility(topic);
    } else {
      const choice = String(rawVisibility).toUpperCase();
      if (!isVisibility(choice)) {
        throw new ViroException('VALIDATION_ERROR', 'Choose everyone, my contacts, or nobody.', HttpStatus.BAD_REQUEST);
      }
      visibility = choice;
    }
    const problem = visibilityProblem(topic, visibility);
    if (problem) throw new ViroException('VALIDATION_ERROR', problem, HttpStatus.BAD_REQUEST);

    if (entries.length === 0) {
      await this.db.query(`DELETE FROM profile_topics WHERE user_id = $1 AND topic = $2`, [userId, topic]);
      return { topic, entries: [], visibility };
    }
    await this.db.query(
      `INSERT INTO profile_topics (user_id, topic, entries, visibility, updated_at)
       VALUES ($1, $2, $3, $4, now())
       ON CONFLICT (user_id, topic)
       DO UPDATE SET entries = EXCLUDED.entries, visibility = EXCLUDED.visibility, updated_at = now()`,
      [userId, topic, entries, visibility],
    );
    return { topic, entries, visibility };
  }

  /**
   * What [viewerId] may read about [ownerId]: only topics that have
   * something in them, and only the ones each setting allows.
   */
  async visibleTo(viewerId: string, ownerId: string): Promise<{ topic: Topic; entries: string[] }[]> {
    const rows: { topic: string; entries: string[]; visibility: string }[] = await this.db.query(
      `SELECT topic, entries, visibility FROM profile_topics
       WHERE user_id = $1 AND cardinality(entries) > 0`,
      [ownerId],
    );
    const out: { topic: Topic; entries: string[] }[] = [];
    for (const topic of TOPICS) {
      const row = rows.find((r) => r.topic === topic);
      if (!row) continue;
      if (await this.visibility.canSee(viewerId, ownerId, row.visibility)) {
        out.push({ topic, entries: row.entries });
      }
    }
    return out;
  }

  /** Gone with the account. Nothing about somebody's fears outlives them leaving. */
  async wipe(userId: string): Promise<void> {
    await this.db.query(`DELETE FROM profile_topics WHERE user_id = $1`, [userId]);
  }
}
