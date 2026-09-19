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
)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val value: String)

data class ConversationRow(
    val id: String,
    val kind: String,
    val peerUserId: String?,
    val title: String?,
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
    val lastId: String?,
    val lastBody: String?,
    val lastType: String?,
    val lastSender: String?,
    val lastAt: Long?,
    val lastDeleted: Long?,
    val lastStatus: String?,
)

@Dao
interface MessagingDao {
    @Query(
        """
        SELECT c.id, c.kind, c.peerUserId, c.title, c.participantsCsv, c.unread, c.updatedAt, c.hidden,
               c.mutedUntil, c.clearedAt, c.disappearingSeconds, c.expiresAt, c.peerLastReadAt,
               c.peerLastDeliveredAt, c.pinnedCsv, c.locked,
               m.id AS lastId, m.body AS lastBody, m.type AS lastType, m.senderUserId AS lastSender,
               m.createdAt AS lastAt, m.deletedAt AS lastDeleted, m.status AS lastStatus
        FROM conversations c
        LEFT JOIN messages m ON m.id = (
            SELECT id FROM messages x WHERE x.conversationId = c.id ORDER BY x.createdAt DESC LIMIT 1
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

    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY createdAt DESC")
    fun observeStarred(): Flow<List<MessageEntity>>

    @Query("SELECT MIN(createdAt) FROM messages WHERE conversationId = :conversationId AND type != 'SYSTEM'")
    suspend fun firstMessageAt(conversationId: String): Long?

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
        wipeMessages()
        wipeConversations()
        wipeKv()
    }
}

@Database(
    entities = [MessageEntity::class, ConversationEntity::class, KvEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MessagingDatabase : RoomDatabase() {
    abstract fun dao(): MessagingDao

    companion object {
        @Volatile private var instance: MessagingDatabase? = null

        fun get(context: Context): MessagingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagingDatabase::class.java,
                    "viro_messaging.db",
                )
                    // Everything here is a cache of the server plus an outbox;
                    // a schema bump may rebuild it and the next sync refills it.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
