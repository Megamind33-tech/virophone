import { Injectable, Logger } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';

export interface MergeSummary {
  survivingUserId: string;
  mergedUserId: string;
  moved: Record<string, number>;
  dropped: Record<string, number>;
}

/**
 * Folds one Viro account into another so a person who signed up twice — once by
 * email, once by phone — ends up with a single account reachable either way.
 *
 * This is destructive and has no undo: the merged account's row is deleted at
 * the end. It runs in ONE transaction, so a failure part-way leaves both
 * accounts exactly as they were rather than half-merged, which would be far
 * worse than either outcome.
 *
 * Every user-referencing column is rewritten — 23 of them across 19 tables.
 * Missing one would orphan that data against a user id that no longer exists.
 *
 * Collisions are the hard part, not the rewriting. Where a table has a unique
 * or primary key that includes the user id, the surviving account's row wins and
 * the merged account's duplicate is dropped: keeping both is impossible, and
 * preferring the survivor keeps the account the user is left signed in as
 * self-consistent. Counts of what moved and what was dropped are returned so
 * the outcome is inspectable rather than silent.
 */
@Injectable()
export class AccountMergeService {
  private readonly logger = new Logger('AccountMergeService');

  constructor(@InjectDataSource() private readonly dataSource: DataSource) {}

  async merge(survivingUserId: string, mergedUserId: string): Promise<MergeSummary> {
    if (survivingUserId === mergedUserId) {
      throw new Error('Cannot merge an account into itself.');
    }

    const moved: Record<string, number> = {};
    const dropped: Record<string, number> = {};

    await this.dataSource.transaction(async (tx) => {
      const move = async (label: string, sql: string) => {
        const res = await tx.query(sql, [survivingUserId, mergedUserId]);
        moved[label] = Array.isArray(res) ? res.length : (res?.[1] ?? 0);
      };
      const drop = async (label: string, sql: string) => {
        const res = await tx.query(sql, [survivingUserId, mergedUserId]);
        dropped[label] = Array.isArray(res) ? res.length : (res?.[1] ?? 0);
      };

      // --- Simple ownership transfers: no constraint includes the user id ----
      await move('calls_as_caller', `UPDATE calls SET caller_user_id = $1 WHERE caller_user_id = $2 RETURNING id`);
      await move('calls_as_callee', `UPDATE calls SET callee_user_id = $1 WHERE callee_user_id = $2 RETURNING id`);
      await move('messages_sent', `UPDATE messages SET sender_user_id = $1 WHERE sender_user_id = $2 RETURNING id`);
      await move('devices', `UPDATE devices SET user_id = $1 WHERE user_id = $2 RETURNING id`);
      await move('sessions', `UPDATE sessions SET user_id = $1 WHERE user_id = $2 RETURNING id`);
      await move('push_tokens', `UPDATE push_tokens SET user_id = $1 WHERE user_id = $2 RETURNING id`);
      await move('security_events', `UPDATE security_events SET user_id = $1 WHERE user_id = $2 RETURNING id`);
      await move('subscriptions', `UPDATE subscriptions SET user_id = $1 WHERE user_id = $2 RETURNING id`);

      // A call between the two accounts becomes a call to oneself, which is not
      // a real call and would show up in history as one.
      await drop(
        'self_calls',
        `DELETE FROM calls WHERE caller_user_id = $1 AND callee_user_id = $1 RETURNING id`,
      );

      // --- Identities: both sign-in methods must reach the survivor ----------
      await move('phone_identities', `UPDATE phone_identities SET user_id = $1 WHERE user_id = $2 RETURNING id`);
      await move('email_identities', `UPDATE email_identities SET user_id = $1 WHERE user_id = $2 RETURNING id`);

      // --- UNIQUE(user_id, phone_e164): survivor's preference wins -----------
      await drop(
        'contact_preferences_dupe',
        `DELETE FROM contact_preferences cp
         WHERE cp.user_id = $2
           AND EXISTS (
             SELECT 1 FROM contact_preferences keep
             WHERE keep.user_id = $1 AND keep.phone_e164 = cp.phone_e164
           )
         RETURNING cp.id`,
      );
      await move(
        'contact_preferences',
        `UPDATE contact_preferences SET user_id = $1 WHERE user_id = $2 RETURNING id`,
      );

      // --- PK(user_id): only one row can exist ------------------------------
      await drop(
        'app_preferences_dupe',
        `DELETE FROM user_app_preferences WHERE user_id = $2
           AND EXISTS (SELECT 1 FROM user_app_preferences WHERE user_id = $1)
         RETURNING user_id`,
      );
      await move(
        'app_preferences',
        `UPDATE user_app_preferences SET user_id = $1 WHERE user_id = $2 RETURNING user_id`,
      );

      // --- UNIQUE(user_id, matched_user_id) ---------------------------------
      // A match pointing at either account also has to be repointed, and a match
      // to oneself is meaningless once the two are the same person.
      await drop(
        'contact_matches_self',
        `DELETE FROM contact_matches
         WHERE (user_id = $2 AND matched_user_id = $1)
            OR (user_id = $1 AND matched_user_id = $2)
         RETURNING id`,
      );
      await drop(
        'contact_matches_dupe',
        `DELETE FROM contact_matches cm
         WHERE cm.user_id = $2
           AND EXISTS (
             SELECT 1 FROM contact_matches keep
             WHERE keep.user_id = $1 AND keep.matched_user_id = cm.matched_user_id
           )
         RETURNING cm.id`,
      );
      await move('contact_matches', `UPDATE contact_matches SET user_id = $1 WHERE user_id = $2 RETURNING id`);
      await drop(
        'contact_matches_inbound_dupe',
        `DELETE FROM contact_matches cm
         WHERE cm.matched_user_id = $2
           AND EXISTS (
             SELECT 1 FROM contact_matches keep
             WHERE keep.user_id = cm.user_id AND keep.matched_user_id = $1
           )
         RETURNING cm.id`,
      );
      await move(
        'contact_matches_inbound',
        `UPDATE contact_matches SET matched_user_id = $1 WHERE matched_user_id = $2 RETURNING id`,
      );

      // --- PK(conversation_id, user_id) -------------------------------------
      await drop(
        'conversation_participants_dupe',
        `DELETE FROM conversation_participants cp
         WHERE cp.user_id = $2
           AND EXISTS (
             SELECT 1 FROM conversation_participants keep
             WHERE keep.conversation_id = cp.conversation_id AND keep.user_id = $1
           )
         RETURNING cp.conversation_id`,
      );
      await move(
        'conversation_participants',
        `UPDATE conversation_participants SET user_id = $1 WHERE user_id = $2 RETURNING conversation_id`,
      );

      // --- PK(message_id, user_id) ------------------------------------------
      await drop(
        'message_receipts_dupe',
        `DELETE FROM message_receipts mr
         WHERE mr.user_id = $2
           AND EXISTS (
             SELECT 1 FROM message_receipts keep
             WHERE keep.message_id = mr.message_id AND keep.user_id = $1
           )
         RETURNING mr.message_id`,
      );
      await move(
        'message_receipts',
        `UPDATE message_receipts SET user_id = $1 WHERE user_id = $2 RETURNING message_id`,
      );

      // --- UNIQUE(requester, recipient) -------------------------------------
      // A connection between the two accounts is a connection to oneself.
      await drop(
        'connections_self',
        `DELETE FROM viro_connections
         WHERE (requester_user_id = $2 AND recipient_user_id = $1)
            OR (requester_user_id = $1 AND recipient_user_id = $2)
         RETURNING id`,
      );
      await drop(
        'connections_dupe',
        `DELETE FROM viro_connections vc
         WHERE vc.requester_user_id = $2
           AND EXISTS (
             SELECT 1 FROM viro_connections keep
             WHERE keep.requester_user_id = $1 AND keep.recipient_user_id = vc.recipient_user_id
           )
         RETURNING vc.id`,
      );
      await move(
        'connections_outbound',
        `UPDATE viro_connections SET requester_user_id = $1 WHERE requester_user_id = $2 RETURNING id`,
      );
      await drop(
        'connections_inbound_dupe',
        `DELETE FROM viro_connections vc
         WHERE vc.recipient_user_id = $2
           AND EXISTS (
             SELECT 1 FROM viro_connections keep
             WHERE keep.requester_user_id = vc.requester_user_id AND keep.recipient_user_id = $1
           )
         RETURNING vc.id`,
      );
      await move(
        'connections_inbound',
        `UPDATE viro_connections SET recipient_user_id = $1 WHERE recipient_user_id = $2 RETURNING id`,
      );

      // --- PK(blocker, blocked) ---------------------------------------------
      // Self-blocks are nonsense; duplicates collapse to the survivor's row.
      await drop(
        'blocks_self',
        `DELETE FROM blocks
         WHERE (blocker_user_id = $2 AND blocked_user_id = $1)
            OR (blocker_user_id = $1 AND blocked_user_id = $2)
         RETURNING blocker_user_id`,
      );
      await drop(
        'blocks_dupe',
        `DELETE FROM blocks b
         WHERE b.blocker_user_id = $2
           AND EXISTS (
             SELECT 1 FROM blocks keep
             WHERE keep.blocker_user_id = $1 AND keep.blocked_user_id = b.blocked_user_id
           )
         RETURNING b.blocker_user_id`,
      );
      await move(
        'blocks_outbound',
        `UPDATE blocks SET blocker_user_id = $1 WHERE blocker_user_id = $2 RETURNING blocker_user_id`,
      );
      await drop(
        'blocks_inbound_dupe',
        `DELETE FROM blocks b
         WHERE b.blocked_user_id = $2
           AND EXISTS (
             SELECT 1 FROM blocks keep
             WHERE keep.blocker_user_id = b.blocker_user_id AND keep.blocked_user_id = $1
           )
         RETURNING b.blocked_user_id`,
      );
      await move(
        'blocks_inbound',
        `UPDATE blocks SET blocked_user_id = $1 WHERE blocked_user_id = $2 RETURNING blocked_user_id`,
      );

      // --- PK(user_id, peer_user_id) ----------------------------------------
      await drop(
        'offline_trust_self',
        `DELETE FROM offline_trust_epochs
         WHERE (user_id = $2 AND peer_user_id = $1) OR (user_id = $1 AND peer_user_id = $2)
         RETURNING user_id`,
      );
      await drop(
        'offline_trust_dupe',
        `DELETE FROM offline_trust_epochs o
         WHERE o.user_id = $2
           AND EXISTS (
             SELECT 1 FROM offline_trust_epochs keep
             WHERE keep.user_id = $1 AND keep.peer_user_id = o.peer_user_id
           )
         RETURNING o.user_id`,
      );
      await move(
        'offline_trust',
        `UPDATE offline_trust_epochs SET user_id = $1 WHERE user_id = $2 RETURNING user_id`,
      );
      await drop(
        'offline_trust_peer_dupe',
        `DELETE FROM offline_trust_epochs o
         WHERE o.peer_user_id = $2
           AND EXISTS (
             SELECT 1 FROM offline_trust_epochs keep
             WHERE keep.user_id = o.user_id AND keep.peer_user_id = $1
           )
         RETURNING o.user_id`,
      );
      await move(
        'offline_trust_peer',
        `UPDATE offline_trust_epochs SET peer_user_id = $1 WHERE peer_user_id = $2 RETURNING user_id`,
      );

      // --- Profile: PK(user_id), so the survivor's profile is the one kept ---
      // Empty fields are filled from the merged profile rather than discarded:
      // if the survivor never set a display name or photo and the other account
      // did, throwing that away would be a visible regression for the user.
      await tx.query(
        `UPDATE profiles keep SET
           display_name = CASE
             WHEN COALESCE(NULLIF(TRIM(keep.display_name), ''), '') = ''
               THEN COALESCE(lose.display_name, keep.display_name)
             ELSE keep.display_name END,
           avatar_url = COALESCE(keep.avatar_url, lose.avatar_url)
         FROM profiles lose
         WHERE keep.user_id = $1 AND lose.user_id = $2`,
        [survivingUserId, mergedUserId],
      );

      // Deleting the user cascades its profile, and anything above that was
      // already repointed is now safely attached to the survivor.
      const deleted = await tx.query(`DELETE FROM users WHERE id = $1 RETURNING id`, [
        mergedUserId,
      ]);
      dropped['users'] = Array.isArray(deleted) ? deleted.length : 0;
    });

    this.logger.log(
      `ACCOUNT_MERGED surviving=${survivingUserId} merged=${mergedUserId} ` +
        `moved=${JSON.stringify(moved)} dropped=${JSON.stringify(dropped)}`,
    );
    return { survivingUserId, mergedUserId, moved, dropped };
  }
}
