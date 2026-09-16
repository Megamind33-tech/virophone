import { Logger } from '@nestjs/common';

export interface PushPayload {
  title?: string;
  body?: string;
  /** Arbitrary string map delivered to the client (e.g. call/message metadata). */
  data?: Record<string, string>;
  /** High priority wakes the device promptly (used for incoming calls). */
  highPriority?: boolean;
}

export interface PushSender {
  send(tokens: string[], payload: PushPayload): Promise<void>;
}

/** No-op sender that logs — the safe default until push credentials are set. */
export class LogPushSender implements PushSender {
  private readonly logger = new Logger('LogPushSender');
  async send(tokens: string[], payload: PushPayload): Promise<void> {
    this.logger.log(
      `[push:log] -> ${tokens.length} token(s): ${JSON.stringify({
        title: payload.title,
        data: payload.data,
      })}`,
    );
  }
}

/**
 * Firebase Cloud Messaging sender (legacy HTTP API).
 * Requires FCM_SERVER_KEY. Sends a data+notification message per token batch.
 */
export class FcmPushSender implements PushSender {
  private readonly logger = new Logger('FcmPushSender');
  private readonly serverKey = process.env.FCM_SERVER_KEY || '';
  private readonly endpoint =
    process.env.FCM_ENDPOINT || 'https://fcm.googleapis.com/fcm/send';

  async send(tokens: string[], payload: PushPayload): Promise<void> {
    if (!this.serverKey) {
      throw new Error('FcmPushSender requires FCM_SERVER_KEY.');
    }
    if (tokens.length === 0) return;

    const body = {
      registration_ids: tokens,
      priority: payload.highPriority ? 'high' : 'normal',
      data: payload.data ?? {},
      notification:
        payload.title || payload.body
          ? { title: payload.title, body: payload.body }
          : undefined,
    };

    const res = await fetch(this.endpoint, {
      method: 'POST',
      headers: {
        Authorization: `key=${this.serverKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      throw new Error(`FCM responded ${res.status}: ${detail.slice(0, 200)}`);
    }
    this.logger.log(`[push:fcm] sent to ${tokens.length} token(s)`);
  }
}

/** Selects the push sender from PUSH_PROVIDER env: fcm | log (default). */
export function createPushSender(): PushSender {
  const kind = (process.env.PUSH_PROVIDER || 'log').toLowerCase();
  if (kind === 'fcm') return new FcmPushSender();
  return new LogPushSender();
}
