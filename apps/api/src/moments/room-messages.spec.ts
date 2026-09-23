import { roomMessages, MessageRow } from './room-messages';

const row = (over: Partial<MessageRow>): MessageRow => ({
  id: 'm1', moment_id: 'room', sender_user_id: 'natasha', sender_device_id: 'd1',
  display_name: 'Natasha', body: 'hello', ciphertext: null, envelope_type: null,
  created_at: '2026-09-23T10:00:00.000Z', reply_to_id: null, ...over,
});

describe('what a room hands back', () => {
  it('keeps a sealed message this device cannot open', () => {
    // The bug this exists for: the envelope join only ever finds the copy
    // addressed to the asking device, so a device with no copy saw the row
    // disappear entirely — after it had already arrived over the socket and
    // been read. Somebody's remark vanishing is worse than one it cannot open.
    const out = roomMessages([row({ id: 'sealed', body: null, ciphertext: null })], new Map());
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ id: 'sealed', sealed: true, body: null, envelope: null });
  });

  it('hands over the envelope addressed to this device', () => {
    const out = roomMessages([row({ body: null, ciphertext: 'abc', envelope_type: 3 })], new Map());
    expect(out[0].envelope).toEqual({ ciphertext: 'abc', type: 3 });
    expect(out[0].sealed).toBe(true);
  });

  it('reads oldest first, however they were fetched', () => {
    // The rows come back newest first because that is how the newest hundred
    // are taken; a room is read the other way round.
    const out = roomMessages(
      [
        row({ id: 'newest', created_at: '2026-09-23T10:02:00.000Z' }),
        row({ id: 'oldest', created_at: '2026-09-23T10:00:00.000Z' }),
      ],
      new Map(),
    );
    expect(out.map((m) => m.id)).toEqual(['oldest', 'newest']);
  });

  it('carries what a message answers, and nothing of what it said', () => {
    const out = roomMessages([row({ reply_to_id: 'earlier' })], new Map());
    expect(out[0].replyToId).toBe('earlier');
    // Only the link: resolving it is the client's job, because in a sealed
    // room the server cannot read the remark being answered.
    expect(Object.keys(out[0])).not.toContain('replyToBody');
  });

  it('gives a nameless sender something to be called', () => {
    expect(roomMessages([row({ display_name: null })], new Map())[0].senderName).toBe('Viro user');
  });

  it('attaches reactions to the message they belong to', () => {
    const reactions = new Map([['m1', [{ emoji: '❤️', userIds: ['a', 'b'] }]]]);
    expect(roomMessages([row({})], reactions)[0].reactions).toEqual([{ emoji: '❤️', userIds: ['a', 'b'] }]);
  });
});
