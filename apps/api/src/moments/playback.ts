/**
 * Watching or listening together: one clock for the whole room.
 *
 * The server keeps a single record of where playback is — what is loaded,
 * whether it is playing, and the position it was at a known instant — and
 * never streams positions. Every phone works out "where are we now" itself
 * from that record and the server's clock, and corrects only if it has
 * drifted. A pause, a seek or someone arriving late changes the record once,
 * and everyone follows.
 */

export const PLAYBACK_OPS = ['LOAD', 'PLAY', 'PAUSE', 'SEEK', 'STOP'] as const;
export type PlaybackOp = (typeof PLAYBACK_OPS)[number];
export type PlaybackStatus = 'IDLE' | 'PLAYING' | 'PAUSED';
export type MediaKind = 'VIDEO' | 'AUDIO';

export interface MomentPlayback {
  momentId: string;
  /** Increases with every change; phones keep the highest they have seen. */
  revision: number;
  mediaId: string | null;
  kind: MediaKind | null;
  title: string | null;
  durationMs: number | null;
  status: PlaybackStatus;
  /** Where playback was at [anchorAt]. */
  positionMs: number;
  /** Server time, epoch ms, that [positionMs] describes. */
  anchorAt: number;
  rate: number;
  updatedBy: string | null;
}

export interface PlaybackChange {
  op: PlaybackOp;
  mediaId?: string;
  positionMs?: number;
}

/** What LOAD needs to know about the thing being loaded. */
export interface PlayableMedia {
  id: string;
  kind: MediaKind;
  title: string;
  durationMs: number | null;
}

export class PlaybackError extends Error {}

export function idlePlayback(momentId: string, now: number): MomentPlayback {
  return {
    momentId, revision: 0, mediaId: null, kind: null, title: null, durationMs: null,
    status: 'IDLE', positionMs: 0, anchorAt: now, rate: 1, updatedBy: null,
  };
}

/** Where playback is at [now], for anyone who asks. Never past the end. */
export function positionAt(p: MomentPlayback, now: number): number {
  const moved = p.status === 'PLAYING' ? Math.max(0, now - p.anchorAt) * p.rate : 0;
  const at = p.positionMs + moved;
  return p.durationMs != null ? Math.min(at, p.durationMs) : at;
}

function clamp(p: MomentPlayback, positionMs: number | undefined, fallback: number): number {
  const raw = positionMs == null || !Number.isFinite(positionMs) ? fallback : Math.round(positionMs);
  const max = p.durationMs ?? Number.MAX_SAFE_INTEGER;
  return Math.min(Math.max(0, raw), max);
}

/**
 * Applies one person's change to the room's playback.
 *
 * Loading starts paused at the beginning: the room gets ready together, and
 * whoever loaded it presses play. A pause is recorded at the position the
 * person pausing saw, so what they stopped on is what everyone stops on.
 */
export function applyPlayback(
  p: MomentPlayback,
  change: PlaybackChange,
  by: string,
  now: number,
  media?: PlayableMedia | null,
): MomentPlayback {
  const next = (patch: Partial<MomentPlayback>): MomentPlayback =>
    ({ ...p, ...patch, revision: p.revision + 1, anchorAt: now, updatedBy: by });

  switch (change.op) {
    case 'LOAD':
      if (!media) throw new PlaybackError('That is no longer here to play.');
      return next({
        mediaId: media.id, kind: media.kind, title: media.title, durationMs: media.durationMs,
        status: 'PAUSED', positionMs: 0, rate: 1,
      });
    case 'PLAY': {
      if (!p.mediaId) throw new PlaybackError('Choose something to play first.');
      let from = clamp(p, change.positionMs, positionAt(p, now));
      // Pressing play at the very end starts it again, the way a player does.
      if (p.durationMs != null && from >= p.durationMs) from = 0;
      return next({ status: 'PLAYING', positionMs: from });
    }
    case 'PAUSE':
      if (!p.mediaId) throw new PlaybackError('Nothing is playing.');
      return next({ status: 'PAUSED', positionMs: clamp(p, change.positionMs, positionAt(p, now)) });
    case 'SEEK':
      if (!p.mediaId) throw new PlaybackError('Nothing is playing.');
      if (change.positionMs == null) throw new PlaybackError('Where to?');
      return next({ positionMs: clamp(p, change.positionMs, 0) });
    case 'STOP':
      return next({ mediaId: null, kind: null, title: null, durationMs: null, status: 'IDLE', positionMs: 0 });
    default:
      throw new PlaybackError('That is not something a player does.');
  }
}
