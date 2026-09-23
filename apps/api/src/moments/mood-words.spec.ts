import { moodInvitation } from './mood-words';

describe('what the people you chose are told', () => {
  it('joins how you are to what you are asking for', () => {
    expect(moodInvitation('Mosty', 'SAD', 'TALK').title).toBe('Mosty is a bit low and would like to talk');
    expect(moodInvitation('Mosty', 'CRAZY', 'TALK').title).toBe('Mosty is all over the place and would like to talk');
  });

  it('never reads the mood as the request', () => {
    // The point of the whole feature: being wound up does not mean wanting to
    // be left alone, and being low does not mean wanting to talk. Only the
    // person says which, by choosing the activity.
    expect(moodInvitation('Mosty', 'ANGRY', 'BE').title).toBe('Mosty is wound up and would like some company');
    expect(moodInvitation('Mosty', 'ANGRY', 'TALK').title).toBe('Mosty is wound up and would like to talk');
    expect(moodInvitation('Mosty', 'SAD', 'STAY').title).toBe('Mosty is a bit low and would like someone to stay a while');
  });

  it('says something sensible when no mood was given', () => {
    // Most Moments carry no mood, and a required one would turn opening a door
    // into a form. The sentence still has to read like a person wrote it.
    expect(moodInvitation('Natasha', null, 'WALK').title).toBe('Natasha would like someone to walk with');
    expect(moodInvitation('Natasha', undefined, 'BE').title).toBe('Natasha would like some company');
  });

  it('lets the host speak over anything generated', () => {
    const told = moodInvitation('Mosty', 'HAPPY', 'COOK', '  Come keep me company  ');
    expect(told.body).toBe('Come keep me company');
    expect(told.title).toBe('Mosty is in good spirits and would like some company while cooking');
  });

  it('falls back to a plain line rather than inventing a feeling', () => {
    expect(moodInvitation('Mosty', 'HAPPY', 'BE', '   ').body).toBe('Tap to step in.');
  });

  it('survives a mood or intent it has never heard of', () => {
    expect(moodInvitation('Mosty', 'PUZZLED' as never, 'BE').title).toBe('Mosty would like some company');
    expect(moodInvitation('Mosty', 'SAD', 'KNITTING' as never).title).toBe('Mosty is a bit low and would like some company');
  });
});
