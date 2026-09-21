import { applyChange, initialState, intentForLegacyType, RoomChangeError } from './room-engine';

const at = new Date('2026-09-21T18:00:00Z');

describe('Moment room engine', () => {
  it('opens a cooking Moment as live presence in a kitchen', () => {
    const s = initialState('m1', 'COOK', 'natasha', at);
    expect(s).toMatchObject({ primary: 'PRESENCE', scene: 'KITCHEN', secondary: [], revision: 1 });
  });

  it('opens Just stay as quiet presence', () => {
    expect(initialState('m1', 'STAY', 'a', at)).toMatchObject({ primary: 'QUIET', scene: 'QUIET' });
  });

  it('keeps one Moment through cook, music, dinner, a film and quiet', () => {
    let s = initialState('m1', 'COOK', 'natasha', at);

    s = applyChange(s, { op: 'ADD', module: 'MUSIC' }, 'natasha', at);
    expect(s).toMatchObject({ primary: 'PRESENCE', secondary: ['MUSIC'], scene: 'KITCHEN' });

    // Dinner and talk: the song carries on.
    s = applyChange(s, { op: 'TRANSFORM', intent: 'TALK' }, 'mosty', at);
    expect(s).toMatchObject({ primary: 'PRESENCE', secondary: ['MUSIC'], scene: 'NEUTRAL' });

    // A film: the room becomes the player, and the music keeps its place beside it.
    s = applyChange(s, { op: 'TRANSFORM', intent: 'WATCH' }, 'natasha', at);
    expect(s).toMatchObject({ primary: 'VIDEO', scene: 'CINEMA' });

    // Quiet: the tools go, music may stay, nothing else.
    s = applyChange(s, { op: 'ADD', module: 'TIMER' }, 'mosty', at);
    s = applyChange(s, { op: 'TRANSFORM', intent: 'STAY' }, 'mosty', at);
    expect(s).toMatchObject({ primary: 'QUIET', scene: 'QUIET', secondary: ['MUSIC'] });

    expect(s.momentId).toBe('m1');
    expect(s.revision).toBe(6);
    expect(s.updatedBy).toBe('mosty');
  });

  it('lets music become the room, and stop being beside it', () => {
    let s = initialState('m1', 'COOK', 'a', at);
    s = applyChange(s, { op: 'ADD', module: 'MUSIC' }, 'a', at);
    s = applyChange(s, { op: 'TRANSFORM', intent: 'LISTEN' }, 'a', at);
    expect(s.primary).toBe('MUSIC');
    expect(s.secondary).not.toContain('MUSIC');
  });

  it('keeps a scene someone chose by hand, until they give it back', () => {
    let s = initialState('m1', 'COOK', 'a', at);
    s = applyChange(s, { op: 'SCENE', scene: 'FAMILY' }, 'a', at);
    s = applyChange(s, { op: 'TRANSFORM', intent: 'WATCH' }, 'b', at);
    expect(s.scene).toBe('FAMILY');
    s = applyChange(s, { op: 'SCENE', scene: null }, 'a', at);
    expect(s).toMatchObject({ scene: 'CINEMA', scenePinned: false });
  });

  it('refuses a change that would leave a room with no primary experience', () => {
    const s = initialState('m1', 'BE', 'a', at);
    expect(() => applyChange(s, { op: 'ADD', module: 'PRESENCE' }, 'a', at)).toThrow(RoomChangeError);
    expect(() => applyChange(s, { op: 'REMOVE', module: 'TIMER' }, 'a', at)).toThrow(RoomChangeError);
    expect(() => applyChange(s, { op: 'TRANSFORM', intent: 'DANCE' as never }, 'a', at)).toThrow(RoomChangeError);
  });

  it('does not add the same thing twice', () => {
    let s = initialState('m1', 'BE', 'a', at);
    s = applyChange(s, { op: 'ADD', module: 'TIMER' }, 'a', at);
    s = applyChange(s, { op: 'ADD', module: 'TIMER' }, 'b', at);
    expect(s.secondary).toEqual(['TIMER']);
  });

  it('opens Moments made before intents existed into a sensible room', () => {
    expect(intentForLegacyType('WATCHING')).toBe('WATCH');
    expect(intentForLegacyType('LISTENING')).toBe('LISTEN');
    expect(intentForLegacyType('FREE')).toBe('BE');
    expect(intentForLegacyType('WORKING')).toBe('STAY');
  });
});
