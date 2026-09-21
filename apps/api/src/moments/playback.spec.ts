import { applyPlayback, idlePlayback, PlaybackError, positionAt } from './playback';

const film = { id: 'film', kind: 'VIDEO' as const, title: 'Our holiday', durationMs: 60_000 };

describe('shared playback: one clock for the room', () => {
  it('loads paused at the start, so the room gets ready together', () => {
    const p = applyPlayback(idlePlayback('m', 0), { op: 'LOAD' }, 'natasha', 1_000, film);
    expect(p).toMatchObject({ mediaId: 'film', status: 'PAUSED', positionMs: 0, revision: 1, updatedBy: 'natasha' });
  });

  it('moves with the clock while playing and stands still while paused', () => {
    let p = applyPlayback(idlePlayback('m', 0), { op: 'LOAD' }, 'n', 0, film);
    p = applyPlayback(p, { op: 'PLAY' }, 'n', 10_000);
    expect(positionAt(p, 10_000)).toBe(0);
    expect(positionAt(p, 25_000)).toBe(15_000);
    p = applyPlayback(p, { op: 'PAUSE' }, 'm', 25_000);
    expect(p.positionMs).toBe(15_000);
    expect(positionAt(p, 99_000)).toBe(15_000);
  });

  it('pauses where the person pausing saw it, and never outside the film', () => {
    let p = applyPlayback(idlePlayback('m', 0), { op: 'LOAD' }, 'n', 0, film);
    p = applyPlayback(p, { op: 'PLAY' }, 'n', 0);
    expect(applyPlayback(p, { op: 'PAUSE', positionMs: 9_400 }, 'm', 10_000).positionMs).toBe(9_400);
    expect(applyPlayback(p, { op: 'PAUSE', positionMs: -5 }, 'm', 10_000).positionMs).toBe(0);
    expect(applyPlayback(p, { op: 'SEEK', positionMs: 999_999 }, 'm', 10_000).positionMs).toBe(60_000);
  });

  it('a seek keeps playing from the new place', () => {
    let p = applyPlayback(idlePlayback('m', 0), { op: 'LOAD' }, 'n', 0, film);
    p = applyPlayback(p, { op: 'PLAY' }, 'n', 0);
    p = applyPlayback(p, { op: 'SEEK', positionMs: 30_000 }, 'm', 5_000);
    expect(p.status).toBe('PLAYING');
    expect(positionAt(p, 7_000)).toBe(32_000);
  });

  it('stops at the end, and play at the end starts it again', () => {
    let p = applyPlayback(idlePlayback('m', 0), { op: 'LOAD' }, 'n', 0, film);
    p = applyPlayback(p, { op: 'PLAY' }, 'n', 0);
    expect(positionAt(p, 500_000)).toBe(60_000);
    p = applyPlayback(p, { op: 'PLAY' }, 'n', 500_000);
    expect(p.positionMs).toBe(0);
  });

  it('refuses what makes no sense, and stop clears the player', () => {
    const idle = idlePlayback('m', 0);
    expect(() => applyPlayback(idle, { op: 'PLAY' }, 'n', 0)).toThrow(PlaybackError);
    expect(() => applyPlayback(idle, { op: 'LOAD' }, 'n', 0, null)).toThrow(PlaybackError);
    let p = applyPlayback(idle, { op: 'LOAD' }, 'n', 0, film);
    expect(() => applyPlayback(p, { op: 'SEEK' }, 'n', 0)).toThrow(PlaybackError);
    p = applyPlayback(p, { op: 'STOP' }, 'n', 0);
    expect(p).toMatchObject({ mediaId: null, status: 'IDLE', revision: 2 });
  });
});
