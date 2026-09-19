import { Logger } from '@nestjs/common';
import type { App, ServiceAccount } from 'firebase-admin/app';
import type { Message } from 'firebase-admin/messaging';

// firebase-admin v14 is ESM-only on these entry points. Loading it lazily keeps
// it out of the module graph for anyone who never sends a push — which is every
// unit test, and any deployment running PUSH_PROVIDER=log.
/* eslint-disable @typescript-eslint/no-var-requires */
function adminApp() {
  return require('firebase-admin/app') as typeof import('firebase-admin/app');
}
function adminMessaging() {
  return require('firebase-admin/messaging') as typeof import('firebase-admin/messaging');
}

export interface PushPayload {
  title?: string;
  body?: string;
  /** Arbitrary string map delivered to the client (e.g. call/message metadata). */
  data?: Record<string, string>;
  /** High priority wakes the device promptly (used for incoming calls). */
  highPriority?: boolean;
}

export interface PushSendResult {
  successCount: number;
  failureCount: number;
  /**
   * Tokens FCM reports as permanently dead (app uninstalled, token rotated).
   * The caller should delete these — otherwise every later send retries them
   * forever and a user's row accumulates tokens that can never be delivered.
   */
  invalidTokens: string[];
}

export interface PushSender {
  send(tokens: string[], payload: PushPayload): Promise<PushSendResult>;
}

const EMPTY_RESULT: PushSendResult = {
  successCount: 0,
  failureCount: 0,
  invalidTokens: [],
};

/** No-op sender that logs — the safe default until push credentials are set. */
export class LogPushSender implements PushSender {
  private readonly logger = new Logger('LogPushSender');
  async send(tokens: string[], payload: PushPayload): Promise<PushSendResult> {
    this.logger.log(
      `[push:log] -> ${tokens.length} token(s): ${JSON.stringify({
        title: payload.title,
        data: payload.data,
      })}`,
    );
    return { ...EMPTY_RESULT, successCount: tokens.length };
  }
}

/**
 * Firebase Cloud Messaging sender, HTTP v1.
 *
 * The previous implementation spoke the LEGACY FCM protocol — `registration_ids`,
 * an `Authorization: key=<server key>` header and the /fcm/send endpoint. Google
 * decommissioned all of that in July 2024, so it could not have delivered a single
 * message regardless of configuration. v1 authenticates with a service account
 * (OAuth2, handled by firebase-admin) and addresses one token per message.
 *
 * Credentials come from FIREBASE_SERVICE_ACCOUNT_JSON (the whole key file as a
 * single env var, which is what the container gets) or GOOGLE_APPLICATION_CREDENTIALS
 * (a path, convenient locally).
 */
export class FcmPushSender implements PushSender {
  private readonly logger = new Logger('FcmPushSender');
  private app: App | null = null;

  private getApp(): App {
    if (this.app) return this.app;

    const inlineJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    const { initializeApp, getApps, cert, applicationDefault } = adminApp();
    const existing = getApps().find((a) => a?.name === APP_NAME);
    if (existing) {
      this.app = existing;
      return existing;
    }

    if (inlineJson) {
      let parsed: ServiceAccount;
      try {
        parsed = JSON.parse(inlineJson) as ServiceAccount;
      } catch {
        throw new Error(
          'FIREBASE_SERVICE_ACCOUNT_JSON is set but is not valid JSON.',
        );
      }
      this.app = initializeApp(
        { credential: cert(parsed) },
        APP_NAME,
      );
    } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
      this.app = initializeApp(
        { credential: applicationDefault() },
        APP_NAME,
      );
    } else {
      throw new Error(
        'FcmPushSender requires FIREBASE_SERVICE_ACCOUNT_JSON or GOOGLE_APPLICATION_CREDENTIALS.',
      );
    }
    return this.app;
  }

  async send(tokens: string[], payload: PushPayload): Promise<PushSendResult> {
    if (tokens.length === 0) return EMPTY_RESULT;
    const messaging = adminMessaging().getMessaging(this.getApp());

    // An incoming call MUST be a data-only message. A message carrying a
    // `notification` block is handed straight to the system tray by the OS when
    // the app is backgrounded, and onMessageReceived never runs — so the app
    // could not raise its full-screen ringer, which is the entire point of the
    // push. Title and body ride along inside data instead.
    const dataOnly = payload.highPriority === true;
    const data: Record<string, string> = { ...(payload.data ?? {}) };
    if (dataOnly) {
      if (payload.title) data.title = payload.title;
      if (payload.body) data.body = payload.body;
    }

    const messages: Message[] = tokens.map((token) => ({
      token,
      data,
      android: {
        priority: payload.highPriority ? 'high' : 'normal',
        // Without a TTL of 0 a call push can be delivered minutes later, long
        // after the caller gave up — a phone ringing for a dead call is worse
        // than not ringing at all.
        ttl: payload.highPriority ? 0 : undefined,
      },
      ...(dataOnly || (!payload.title && !payload.body)
        ? {}
        : { notification: { title: payload.title, body: payload.body } }),
    }));

    const response = await messaging.sendEach(messages);

    const invalidTokens: string[] = [];
    response.responses.forEach((r, i) => {
      if (r.success) return;
      const code = (r.error as { code?: string } | undefined)?.code ?? '';
      if (
        code === 'messaging/registration-token-not-registered' ||
        code === 'messaging/invalid-registration-token' ||
        code === 'messaging/invalid-argument'
      ) {
        invalidTokens.push(tokens[i]);
      } else {
        this.logger.warn(`[push:fcm] send failed code=${code}`);
      }
    });

    this.logger.log(
      `[push:fcm] sent ok=${response.successCount} failed=${response.failureCount} dead=${invalidTokens.length}`,
    );
    return {
      successCount: response.successCount,
      failureCount: response.failureCount,
      invalidTokens,
    };
  }
}

const APP_NAME = 'viro-push';

/** Selects the push sender from PUSH_PROVIDER env: fcm | log (default). */
export function createPushSender(): PushSender {
  const kind = (process.env.PUSH_PROVIDER || 'log').toLowerCase();
  if (kind === 'fcm') return new FcmPushSender();
  return new LogPushSender();
}
