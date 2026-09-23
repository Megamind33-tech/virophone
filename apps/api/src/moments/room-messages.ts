/**
 * Turning stored rows into the messages a room hands back.
 *
 * Pulled out of the service so it can be held to its promises. The promise
 * that matters most is that nothing is dropped: a sealed message is joined to
 * the envelope belonging to the device that asked, and a device that has no
 * envelope for it — one that joined after the remark, or had published no keys
 * when it was sent — used to make the whole row disappear. It arrived over the
 * socket, showed in the room, and then quietly went missing on the next read,
 * which is how somebody's remark vanished in front of them.
 *
 * Unreadable is not the same as unsaid, and the client is already written for
 * the difference.
 */
export interface MessageRow {
  id: string;
  moment_id: string;
  sender_user_id: string;
  sender_device_id: string | null;
  display_name: string | null;
  body: string | null;
  ciphertext: string | null;
  envelope_type: number | null;
  created_at: string | Date;
  reply_to_id: string | null;
}

export interface RoomMessage {
  id: string;
  momentId: string;
  senderUserId: string;
  senderDeviceId: string | null;
  senderName: string;
  body: string | null;
  sealed: boolean;
  envelope: { ciphertext: string; type: number } | null;
  createdAt: string;
  replyToId: string | null;
  reactions: { emoji: string; userIds: string[] }[];
}

/**
 * [rows] arrive newest first, because that is how the limit takes the newest
 * hundred; a room reads oldest first, so they are turned around here.
 */
export function roomMessages(
  rows: MessageRow[],
  reactions: Map<string, { emoji: string; userIds: string[] }[]>,
): RoomMessage[] {
  return [...rows].reverse().map((m) => ({
    id: m.id,
    momentId: m.moment_id,
    senderUserId: m.sender_user_id,
    senderDeviceId: m.sender_device_id,
    senderName: m.display_name || 'Viro user',
    body: m.body,
    sealed: m.body === null,
    envelope: m.ciphertext ? { ciphertext: m.ciphertext, type: m.envelope_type ?? 1 } : null,
    createdAt: new Date(m.created_at).toISOString(),
    replyToId: m.reply_to_id ?? null,
    reactions: reactions.get(m.id) ?? [],
  }));
}
