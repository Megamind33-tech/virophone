import { MomentIntent } from './room-engine';

/**
 * How somebody is, in words other people would actually use.
 *
 * These are the four the mood artwork draws. They are written as a state
 * rather than a label — "a bit low" rather than "SAD" — because the sentence
 * they land in is read by a friend, not by a system.
 */
const MOOD_WORDS: Record<string, string> = {
  HAPPY: 'in good spirits',
  SAD: 'a bit low',
  ANGRY: 'wound up',
  CRAZY: 'all over the place',
};

/**
 * What somebody is asking for, said as an invitation.
 *
 * Deliberately taken from the activity they chose rather than guessed from
 * their mood. Being low and wanting to talk and being low and wanting quiet
 * company are different things, and only the person can say which — the app
 * must never decide that on their behalf.
 */
const WANT_WORDS: Record<MomentIntent, string> = {
  BE: 'would like some company',
  TALK: 'would like to talk',
  WATCH: 'would like to watch something with someone',
  LISTEN: 'would like to listen to something with someone',
  PLAY: 'would like to play something',
  COOK: 'would like some company while cooking',
  WALK: 'would like someone to walk with',
  CHOOSE: 'would like help choosing something',
  LEARN: 'would like to learn something with someone',
  CELEBRATE: 'has something to celebrate',
  REMEMBER: 'would like to remember something with someone',
  STAY: 'would like someone to stay a while',
};

export interface Told {
  title: string;
  body: string;
}

/**
 * What the people you chose are told.
 *
 * Two things are joined: how you are, and what you are asking for. Either can
 * be missing and the sentence still has to read like a person wrote it —
 * plenty of Moments carry no mood at all, and a Moment with a mood and nothing
 * else is still worth hearing about.
 *
 * [invitationText] is the host's own words and always wins the body when it is
 * there. Nothing generated here should talk over somebody who has said what
 * they actually mean.
 */
export function moodInvitation(
  name: string,
  mood: string | null | undefined,
  intent: MomentIntent,
  invitationText?: string | null,
): Told {
  const feeling = mood ? MOOD_WORDS[mood] : undefined;
  const want = WANT_WORDS[intent] ?? 'would like some company';
  const title = feeling
    ? `${name} is ${feeling} and ${want}`
    : `${name} ${want}`;
  return {
    title,
    // Their words if they wrote any; otherwise say plainly what this is, and
    // do not invent a sentiment nobody expressed.
    body: invitationText?.trim() || 'Tap to step in.',
  };
}

/**
 * What a host is told when somebody knocks on their Moment: who is at the door,
 * and nothing about how anybody feels.
 *
 * moodInvitation is the wrong tool here and must not be reached for. It writes
 * "<name> is <how they feel>", and for a knock the only mood to hand is the
 * Moment's — which the host set about themselves. Joining the two told a host
 * who was feeling low that the person knocking was low, with the host's own
 * invitation line as though the visitor had written it. The knocker has said
 * nothing about how they feel, so nothing is said.
 */
export function knockNotice(knockerName: string, activity?: string): Told {
  return {
    title: `${knockerName} wants to join you`,
    body: activity ? `${knockerName} saw ${activity === 'your Moment' ? activity : `your “${activity}” Moment`} and is around.` : 'They knocked on your Moment. Tap to answer.',
  };
}
