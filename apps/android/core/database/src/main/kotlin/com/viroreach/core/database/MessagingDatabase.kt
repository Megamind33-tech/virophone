package com.viroreach.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Messages kept on the phone. Before this they lived only in memory, so a
 * restart or a moment offline showed an empty chat, and a message that missed
 * its live frame never appeared at all.
 *
 * Its own database file, deliberately: the contacts database holds custom
 * names and photos that exist nowhere else, and must never be touched by a
 * messaging schema change.
 */
@Entity(
    tableName = "messages",
    indices = [Index("conversationId", "createdAt"), Index(value = ["clientMsgId"], unique = true)],
)
data class MessageEntity(
    /** Server id once sent; "local:<clientMsgId>" while still in the outbox. */
    @PrimaryKey val id: String,
    val clientMsgId: String?,
    /** Server conversation id, or "peer:<userId>" before the first send creates it. */
    val conversationId: String,
    val senderUserId: String,
    val type: String,
    val body: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val editedAt: Long? = null,
    val deletedAt: Long? = null,
    val expiresAt: Long? = null,
    val deliverAt: Long? = null,
    val replyJson: String? = null,
    val reactionsJson: String? = null,
    val mediaJson: String? = null,
    val viewOnce: Boolean = false,
    val viewed: Boolean = false,
    val forwarded: Boolean = false,
    val starred: Boolean = false,
    val metadataJson: String? = null,
    /** SENDING | FAILED while in the outbox; null once the server has it. */
    val status: String? = null,
    /** A file on this phone: the recording/photo before upload, or a cached download. */
    val localMediaPath: String? = null,
    val pollJson: String? = null,
    /**
     * Where a sealed message stands on this phone, when it is not simply
     * readable: PENDING while it waits to be opened (the retry queue has its
     * ciphertext), UNAVAILABLE when this phone can never open it — it was
     * sealed before this phone was set up, or its key is already spent.
     * Null for everything readable. Only ever read with type = ENCRYPTED.
     */
    val cryptoState: String? = null,
)

/**
 * A sealed message waiting to be opened.
 *
 * The message itself stays in the chat, in its place, while this row keeps
 * what is needed to try again: the server's copy of it (with this device's
 * ciphertext), why the last attempt failed and when the next one is due.
 * Nothing is ever dropped because it could not be opened yet.
 */
@Entity(tableName = "pending_decryption", indices = [Index("nextRetryAt"), Index("senderDeviceId")])
data class PendingDecryptionEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String?,
    /** The message as the server sent it, envelopes trimmed to this device's. */
    val messageJson: String,
    val reason: String,
    val attempts: Int,
    val nextRetryAt: Long,
    /** Out of automatic retries; still retried when the sender's session changes. */
    val gaveUp: Boolean,
    val firstSeenAt: Long,
)

@Entity(tableName = "conversations", indices = [Index("peerUserId")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val peerUserId: String?,
    val title: String?,
    val participantsCsv: String,
    val unread: Int,
    val updatedAt: Long,
    val hidden: Boolean,
    val mutedUntil: Long?,
    val clearedAt: Long?,
    val resetAt: Long?,
    val disappearingSeconds: Int?,
    val expiresAt: Long?,
    val peerLastReadAt: Long?,
    val peerLastDeliveredAt: Long?,
    val pinnedCsv: String,
    /** Chat lock is local to this phone (biometric), never sent anywhere. */
    val locked: Boolean = false,
    val description: String? = null,
    /** ADMIN | MEMBER — my role, for groups. */
    val myRole: String = "MEMBER",
    /** Out of the main inbox, in "Archived". Mine alone. */
    val archived: Boolean = false,
    /** Pinned to the top of the inbox; newest pin first. */
    val pinnedAt: Long? = null,
    /** "Mark as unread", until the chat is opened again. */
    val unreadMarked: Boolean = false,
    /** Someone named me with @ in a message I haven't read. */
    val mentionedUnread: Boolean = false,
    /**
     * End-to-end encrypted: the server carries this chat without being able to
     * read it. Once true it never goes back to false.
     */
    val encrypted: Boolean = false,
)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val value: String)

/** Sealed messages in one conversation that this phone holds without their ciphertext. */
data class SealedGap(val conversationId: String, val oldest: Long, val newest: Long, val count: Int)

data class ConversationRow(
    val id: String,
    val kind: String,
    val peerUserId: String?,
    val title: String?,
    val myRole: String,
    val participantsCsv: String,
    val unread: Int,
    val updatedAt: Long,
    val hidden: Boolean,
    val mutedUntil: Long?,
    val clearedAt: Long?,
    val disappearingSeconds: Int?,
    val expiresAt: Long?,
    val peerLastReadAt: Long?,
    val peerLastDeliveredAt: Long?,
    val pinnedCsv: String,
    val locked: Boolean,
    val archived: Boolean,
    val pinnedAt: Long?,
    val unreadMarked: Boolean,
    val mentionedUnread: Boolean,
    val lastId: String?,
    val lastBody: String?,
    val lastType: String?,
    val lastSender: String?,
    val lastAt: Long?,
    val lastDeleted: Long?,
    val lastStatus: String?,
    val lastCryptoState: String? = null,
)

@Dao
interface MessagingDao {
    @Query(
        """
        SELECT c.id, c.kind, c.peerUserId, c.title, c.myRole, c.participantsCsv, c.unread, c.updatedAt, c.hidden,
               c.mutedUntil, c.clearedAt, c.disappearingSeconds, c.expiresAt, c.peerLastReadAt,
               c.peerLastDeliveredAt, c.pinnedCsv, c.locked, c.archived, c.pinnedAt, c.unreadMarked, c.mentionedUnread,
               m.id AS lastId, m.body AS lastBody, m.type AS lastType, m.senderUserId AS lastSender,
               m.createdAt AS lastAt, m.deletedAt AS lastDeleted, m.status AS lastStatus,
               m.cryptoState AS lastCryptoState
        FROM conversations c
        LEFT JOIN messages m ON m.id = (
            -- The inbox shows the last thing said. A sealed reaction is carried
            -- as a message but belongs on one, so it is not that.
            SELECT id FROM messages x WHERE x.conversationId = c.id AND x.type != 'REACTION'
            ORDER BY x.createdAt DESC LIMIT 1
        )
        ORDER BY COALESCE(m.createdAt, c.updatedAt) DESC
        """,
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE kind = 'DM' AND peerUserId = :peerUserId LIMIT 1")
    suspend fun dmWith(peerUserId: String): ConversationEntity?

    @Query("SELECT * FROM conversations")
    suspend fun allConversations(): List<ConversationEntity>

    /**
     * Everything this phone holds, for the encrypted backup. Bounded because
     * it is all read into memory at once, and a phone that has been messaging
     * for years should not run out of it making a backup.
     */
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    suspend fun allMessages(limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(rows: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteConversations(ids: List<String>)

    @Query("UPDATE conversations SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: String, locked: Boolean)

    @Query("UPDATE conversations SET unread = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    @Query("UPDATE conversations SET peerLastReadAt = MAX(COALESCE(peerLastReadAt, 0), :at), peerLastDeliveredAt = MAX(COALESCE(peerLastDeliveredAt, 0), :at) WHERE id = :id")
    suspend fun bumpPeerRead(id: String, at: Long)

    @Query("UPDATE conversations SET peerLastDeliveredAt = MAX(COALESCE(peerLastDeliveredAt, 0), :at) WHERE id = :id")
    suspend fun bumpPeerDelivered(id: String, at: Long)

    /** Every message holding a file, for working out what storage is used by what. */
    @Query("SELECT * FROM messages WHERE mediaJson IS NOT NULL")
    suspend fun mediaMessages(): List<MessageEntity>

    /** Recent locations, for keeping my own live shares moving. */
    @Query("SELECT * FROM messages WHERE type = 'LOCATION' AND deletedAt IS NULL ORDER BY createdAt DESC LIMIT 50")
    suspend fun liveLocations(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId")
    suspend fun observeMessagesOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE clientMsgId = :clientMsgId LIMIT 1")
    suspend fun byClientMsgId(clientMsgId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(rows: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<String>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversationMessages(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt <= :upTo AND status IS NULL")
    suspend fun deleteMessagesUpTo(conversationId: String, upTo: Long)

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT * FROM messages WHERE status IN ('SENDING', 'FAILED') ORDER BY createdAt ASC")
    suspend fun outbox(): List<MessageEntity>

    @Query("UPDATE messages SET conversationId = :to WHERE conversationId = :from")
    suspend fun moveMessages(from: String, to: String)

    /** Local search; [q] must already have %, _ and ! escaped with a leading !. */
    @Query(
        """
        SELECT * FROM messages
        WHERE deletedAt IS NULL AND viewOnce = 0 AND type IN ('TEXT', 'IMAGE', 'POLL')
          AND (body LIKE '%' || :q || '%' ESCAPE '!' OR pollJson LIKE '%' || :q || '%' ESCAPE '!')
          AND (:conversationId IS NULL OR conversationId = :conversationId)
        ORDER BY createdAt DESC LIMIT 100
        """,
    )
    suspend fun search(q: String, conversationId: String?): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY createdAt DESC")
    fun observeStarred(): Flow<List<MessageEntity>>

    @Query("SELECT MIN(createdAt) FROM messages WHERE conversationId = :conversationId AND type != 'SYSTEM'")
    suspend fun firstMessageAt(conversationId: String): Long?

    @Query("SELECT * FROM pending_decryption WHERE gaveUp = 0 AND nextRetryAt <= :now ORDER BY firstSeenAt ASC LIMIT :limit")
    suspend fun pendingDue(now: Long, limit: Int): List<PendingDecryptionEntity>

    @Query("SELECT * FROM pending_decryption WHERE gaveUp = 0 ORDER BY firstSeenAt ASC LIMIT :limit")
    suspend fun pendingRetryable(limit: Int): List<PendingDecryptionEntity>

    @Query("SELECT * FROM pending_decryption WHERE senderDeviceId = :deviceId ORDER BY firstSeenAt ASC")
    suspend fun pendingFromDevice(deviceId: String): List<PendingDecryptionEntity>

    @Query("SELECT MIN(nextRetryAt) FROM pending_decryption WHERE gaveUp = 0")
    suspend fun nextPendingAt(): Long?

    @Query("SELECT * FROM pending_decryption WHERE messageId = :messageId")
    suspend fun pending(messageId: String): PendingDecryptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePending(row: PendingDecryptionEntity)

    @Query("DELETE FROM pending_decryption WHERE messageId IN (:ids)")
    suspend fun deletePending(ids: List<String>)

    /** Sealed rows from before the retry queue existed: no ciphertext kept on this phone. */
    @Query(
        """
        SELECT conversationId, MIN(createdAt) AS oldest, MAX(createdAt) AS newest, COUNT(*) AS count
        FROM messages WHERE type = 'ENCRYPTED' AND cryptoState IS NULL AND deletedAt IS NULL
        GROUP BY conversationId
        """,
    )
    suspend fun unrecoveredSealed(): List<SealedGap>

    @Query("UPDATE messages SET cryptoState = :state WHERE type = 'ENCRYPTED' AND cryptoState IS NULL AND conversationId = :conversationId")
    suspend fun settleUnrecoveredSealed(conversationId: String, state: String)

    /** When the newest message from someone else in a chat was sent. */
    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :conversationId AND senderUserId != :me AND type != 'SYSTEM'")
    suspend fun latestIncomingAt(conversationId: String, me: String): Long?

    /**
     * Incoming messages newer than a read marker: the badge this phone
     * actually owes, counted from rows it has, rather than the server's
     * count — which cannot shrink while sealed messages hold their
     * read receipts back.
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationId = :conversationId AND senderUserId != :me
          AND deletedAt IS NULL AND type NOT IN ('SYSTEM', 'REACTION')
          AND createdAt > :readAt
        """,
    )
    suspend fun unreadSince(conversationId: String, me: String, readAt: Long): Int

    /** Incoming messages in a chat that this phone has not been able to open yet. */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationId = :conversationId AND type = 'ENCRYPTED' AND deletedAt IS NULL
          AND senderUserId != :me AND (cryptoState IS NULL OR cryptoState != 'UNAVAILABLE')
        """,
    )
    suspend fun unopenedIncoming(conversationId: String, me: String): Int

    /** Sealed rows an earlier build gave up on, put back in line to be asked for again. */
    @Query("UPDATE messages SET cryptoState = NULL WHERE type = 'ENCRYPTED' AND cryptoState = 'UNAVAILABLE' AND deletedAt IS NULL")
    suspend fun requeueUnavailableSealed(): Int

    @Query("DELETE FROM pending_decryption")
    suspend fun wipePending()

    @Query("SELECT * FROM kv WHERE `key` = :key")
    suspend fun kv(key: String): KvEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putKv(row: KvEntity)

    @Query("DELETE FROM messages")
    suspend fun wipeMessages()

    @Query("DELETE FROM conversations")
    suspend fun wipeConversations()

    @Query("DELETE FROM kv")
    suspend fun wipeKv()

    @Transaction
    suspend fun wipeAll() {
        wipePending()
        wipeMessages()
        wipeConversations()
        wipeKv()
    }
}

@Database(
    entities = [MessageEntity::class, ConversationEntity::class, KvEntity::class, PendingDecryptionEntity::class],
    // v2: polls, group roles and descriptions. v5: the encrypted flag.
    // v6: the pending-decryption queue and each message's crypto state.
    version = 6,
    exportSchema = false,
)
abstract class MessagingDatabase : RoomDatabase() {
    abstract fun dao(): MessagingDao

    companion object {
        /** The file every build before per-account storage kept all messages in. */
        private const val LEGACY_NAME = "viro_messaging.db"
        private val instances = java.util.concurrent.ConcurrentHashMap<String, MessagingDatabase>()
        @Volatile private var account: String? = null

        /**
         * Which account's messages are read and written from now on.
         *
         * Each account on this phone keeps its own file. Signing out, or
         * another account signing in, no longer deletes anything: the other
         * account's messages simply are not the ones open. That is what lets
         * two people share a phone, or one person sign out and back in,
         * without losing a conversation — the words in an end-to-end
         * encrypted chat exist nowhere else.
         */
        fun useAccount(accountId: String?) {
            account = accountId
        }

        /**
         * Hands the single file an earlier build kept to the account it belonged
         * to, once. Called before anything opens a database.
         */
        fun adoptLegacy(context: Context, owner: String) {
            adoptFiles(context, LEGACY_NAME, nameFor(owner))
        }

        internal fun nameFor(accountId: String?): String =
            if (accountId == null) "viro_messaging_signed_out.db"
            else "viro_messaging_" + accountId.filter { it.isLetterOrDigit() || it == '-' } + ".db"

        /**
         * From v5 on, schema changes are migrated rather than rebuilt.
         *
         * This used to be a pure cache of the server, so throwing it away cost
         * nothing but a re-sync. That stopped being true with end-to-end
         * encryption: an encrypted message can only be opened once, so the
         * plaintext this phone holds is the only copy it will ever have. The
         * server still has the sealed envelope, and cannot help.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Sealed messages stop being thrown away when they cannot be opened
         * yet. Existing rows keep everything they hold; sealed ones from before
         * this get a null state, which is what marks them for one repair pass
         * against the server's copy.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN cryptoState TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_decryption` (" +
                        "`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `senderUserId` TEXT NOT NULL, " +
                        "`senderDeviceId` TEXT, `messageJson` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `nextRetryAt` INTEGER NOT NULL, `gaveUp` INTEGER NOT NULL, " +
                        "`firstSeenAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_decryption_nextRetryAt` ON `pending_decryption` (`nextRetryAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_decryption_senderDeviceId` ON `pending_decryption` (`senderDeviceId`)")
            }
        }

        fun get(context: Context): MessagingDatabase {
            val name = nameFor(account)
            return instances[name] ?: synchronized(this) {
                instances[name] ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagingDatabase::class.java,
                    name,
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    // Only for a version pair with no migration above — which
                    // now means a bug, not a plan.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instances[name] = it }
            }
        }
    }
}

/**
 * Renames a Room database's files (with its journal and WAL) to a new name,
 * only when the new one does not exist yet and nothing has opened either.
 * Shared by the per-account stores for adopting the file an earlier build left.
 */
fun adoptFiles(context: Context, from: String, to: String) {
    val source = context.getDatabasePath(from)
    val target = context.getDatabasePath(to)
    if (!source.exists() || target.exists()) return
    for (suffix in listOf("", "-wal", "-shm", "-journal")) {
        val s = java.io.File(source.path + suffix)
        if (s.exists()) s.renameTo(java.io.File(target.path + suffix))
    }
}
