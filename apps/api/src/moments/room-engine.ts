/**
 * The rules of a Moment room, as pure functions.
 *
 * A room is one living space whose *primary experience* changes while the
 * people in it stay: cooking becomes dinner becomes a film becomes just being
 * there. Everything here decides what a room becomes when someone changes it,
 * and nothing here touches Redis, the database or a socket — so every client
 * sees the same answer to "what is this room now?", and the answer can be
 * tested on its own.
 */

/** Why people came together. Chosen when a Moment starts; changes as it goes. */
export const MOMENT_INTENTS = [
  'BE', // Be with me
  'TALK', // Talk with me
  'WATCH', // Watch with me
  'LISTEN', // Listen with me
  'PLAY', // Play with me
  'COOK', // Cook with me
  'WALK', // Walk with me
  'CHOOSE', // Help me choose
  'LEARN', // Learn with me
  'CELEBRATE', // Celebrate with me
  'REMEMBER', // Remember with me
  'STAY', // Just stay
] as const;
export type MomentIntent = (typeof MOMENT_INTENTS)[number];

/**
 * What can fill a room. Exactly one is primary at a time — that is what stops
 * a room becoming a dashboard — and the rest sit around it.
 */
export const MOMENT_MODULES = ['PRESENCE', 'QUIET', 'VIDEO', 'MUSIC', 'TIMER', 'CHOICE'] as const;
export type MomentModule = (typeof MOMENT_MODULES)[number];

/** Modules that can be the room. */
const PRIMARY_CAPABLE: MomentModule[] = ['PRESENCE', 'QUIET', 'VIDEO', 'MUSIC'];
/** Modules that sit alongside whatever the room is. */
const SECONDARY_CAPABLE: MomentModule[] = ['MUSIC', 'TIMER', 'CHOICE'];

/** Atmospheres. The phone draws them; this only says which one. */
export const MOMENT_SCENES = [
  'KITCHEN',
  'CINEMA',
  'LISTENING',
  'QUIET',
  'PLAY',
  'FAMILY',
  'CELEBRATION',
  'OUTDOORS',
  'NEUTRAL',
] as const;
export type MomentScene = (typeof MOMENT_SCENES)[number];

/**
 * What the room is right now. Held in Redis, not the database: it changes
 * constantly and matters only while the Moment lives.
 */
export interface MomentRuntimeState {
  momentId: string;
  /** Goes up by one on every change. A phone ignores anything older than what it has. */
  revision: number;
  intent: MomentIntent;
  primary: MomentModule;
  secondary: MomentModule[];
  scene: MomentScene;
  /** Set when someone picked a scene by hand, so the next transformation keeps it. */
  scenePinned: boolean;
  updatedAt: string;
  updatedBy: string | null;
}

/** What a room becomes when people say what they are there to do. */
const INTENT_SHAPES: Record<MomentIntent, { primary: MomentModule; scene: MomentScene; secondary?: MomentModule[] }> = {
  BE: { primary: 'PRESENCE', scene: 'NEUTRAL' },
  TALK: { primary: 'PRESENCE', scene: 'NEUTRAL' },
  WATCH: { primary: 'VIDEO', scene: 'CINEMA' },
  LISTEN: { primary: 'MUSIC', scene: 'LISTENING' },
  PLAY: { primary: 'PRESENCE', scene: 'PLAY', secondary: ['CHOICE'] },
  COOK: { primary: 'PRESENCE', scene: 'KITCHEN' },
  WALK: { primary: 'PRESENCE', scene: 'OUTDOORS' },
  CHOOSE: { primary: 'PRESENCE', scene: 'NEUTRAL', secondary: ['CHOICE'] },
  LEARN: { primary: 'PRESENCE', scene: 'NEUTRAL' },
  CELEBRATE: { primary: 'PRESENCE', scene: 'CELEBRATION' },
  REMEMBER: { primary: 'PRESENCE', scene: 'FAMILY' },
  STAY: { primary: 'QUIET', scene: 'QUIET' },
};

/**
 * What an activity is called when it has to be written down rather than drawn.
 *
 * Only for text the server stores and every phone then reads back the same —
 * a kept memory's title. Anything on screen while the room is live is worded
 * by the app, which knows the person's own language.
 */
const INTENT_WORDS: Record<MomentIntent, string> = {
  BE: 'Time together',
  TALK: 'A talk',
  WATCH: 'Watching together',
  LISTEN: 'Listening together',
  PLAY: 'Playing together',
  COOK: 'Cooking together',
  WALK: 'A walk together',
  CHOOSE: 'Deciding together',
  LEARN: 'Learning together',
  CELEBRATE: 'A celebration',
  REMEMBER: 'Remembering together',
  STAY: 'Quiet time together',
};

export function intentWords(intent: MomentIntent): string {
  return INTENT_WORDS[intent] ?? 'Time together';
}

/** A Moment created before intents existed still opens into a sensible room. */
export function intentForLegacyType(type: string | null | undefined): MomentIntent {
  switch (type) {
    case 'WATCHING':
      return 'WATCH';
    case 'LISTENING':
      return 'LISTEN';
    case 'GAMING':
      return 'PLAY';
    case 'WORKING':
      return 'STAY';
    default:
      return 'BE';
  }
}

/** The room a Moment opens into. */
export function initialState(momentId: string, intent: MomentIntent, by: string | null, now = new Date()): MomentRuntimeState {
  const shape = INTENT_SHAPES[intent];
  return {
    momentId,
    revision: 1,
    intent,
    primary: shape.primary,
    secondary: [...(shape.secondary ?? [])],
    scene: shape.scene,
    scenePinned: false,
    updatedAt: now.toISOString(),
    updatedBy: by,
  };
}

/** Everything someone can do to the shape of a room. */
export type RoomChange =
  | { op: 'TRANSFORM'; intent: MomentIntent }
  | { op: 'ADD'; module: MomentModule }
  | { op: 'REMOVE'; module: MomentModule }
  | { op: 'SCENE'; scene: MomentScene | null };

export class RoomChangeError extends Error {}

/**
 * Applies one change and returns the room it makes.
 *
 * The room is never rebuilt: people, the Moment and its history are not part
 * of this state and so cannot be lost by it. Only what the room *is* moves.
 */
export function applyChange(
  state: MomentRuntimeState,
  change: RoomChange,
  by: string,
  now = new Date(),
): MomentRuntimeState {
  const next: MomentRuntimeState = { ...state, secondary: [...state.secondary] };
  switch (change.op) {
    case 'TRANSFORM': {
      if (!MOMENT_INTENTS.includes(change.intent)) throw new RoomChangeError('Unknown activity.');
      const shape = INTENT_SHAPES[change.intent];
      next.intent = change.intent;
      next.primary = shape.primary;
      // What was already going on and still makes sense keeps going: the song
      // playing while dinner was cooked is still playing when the talking
      // starts. Only something that is now the room itself stops being beside it.
      const carried = state.secondary.filter((m) => m !== shape.primary);
      next.secondary = unique([...carried, ...(shape.secondary ?? [])].filter((m) => m !== shape.primary));
      if (!state.scenePinned) next.scene = shape.scene;
      // Quiet means quiet: a transformation into it takes the tools away.
      if (shape.primary === 'QUIET') next.secondary = next.secondary.filter((m) => m === 'MUSIC');
      break;
    }
    case 'ADD': {
      if (!SECONDARY_CAPABLE.includes(change.module)) throw new RoomChangeError('That cannot be added alongside.');
      if (change.module === state.primary) throw new RoomChangeError('That is already the room.');
      if (!next.secondary.includes(change.module)) next.secondary.push(change.module);
      break;
    }
    case 'REMOVE': {
      if (!next.secondary.includes(change.module)) throw new RoomChangeError('That is not in the room.');
      next.secondary = next.secondary.filter((m) => m !== change.module);
      break;
    }
    case 'SCENE': {
      if (change.scene === null) {
        // Unpinning hands the scene back to the activity.
        next.scenePinned = false;
        next.scene = INTENT_SHAPES[state.intent].scene;
      } else {
        if (!MOMENT_SCENES.includes(change.scene)) throw new RoomChangeError('Unknown scene.');
        next.scene = change.scene;
        next.scenePinned = true;
      }
      break;
    }
    default:
      throw new RoomChangeError('Unknown change.');
  }
  if (!PRIMARY_CAPABLE.includes(next.primary)) throw new RoomChangeError('That cannot be the room.');
  next.revision = state.revision + 1;
  next.updatedAt = now.toISOString();
  next.updatedBy = by;
  return next;
}

function unique<T>(xs: T[]): T[] {
  return xs.filter((x, i) => xs.indexOf(x) === i);
}
