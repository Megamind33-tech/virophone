package com.viroreach.core.e2ee

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Where this phone's encryption keys live.
 *
 * Unlike the message cache, **nothing here can be rebuilt from the server**.
 * The identity key is what other people's safety numbers are built from, and
 * the sessions are the only way to read messages already sent to this device.
 * So this database never falls back to a destructive migration: a schema
 * change must be migrated, or the person silently loses their chats.
 *
 * It holds private keys, which is why it is the app's own file in internal
 * storage and never leaves it.
 */
@Entity(tableName = "own_identity")
data class OwnIdentityEntity(
    /** Always 1: one identity per installation. */
    @PrimaryKey val id: Int = 1,
    /** Whose identity this is. Keys never survive a different person signing in. */
    val userId: String,
    /** The server's id for this device — what other people address envelopes to. */
    val deviceId: String,
    val registrationId: Int,
    /** The serialized key pair. Private: it never leaves this table. */
    val identityKeyPair: ByteArray,
    val createdAt: Long,
    /** Ids continue upward rather than being reused after a rotation. */
    val nextPreKeyId: Int,
    val nextSignedPreKeyId: Int,
    val nextKyberPreKeyId: Int,
    /** When the published signed/Kyber prekeys were last replaced. */
    val signedPrekeyRotatedAt: Long,
    /**
     * When the server last accepted this device's public keys — 0 when it
     * never has.
     *
     * Generating keys and publishing them are two steps and only the first is
     * local. If the upload failed, the phone still looked registered to itself
     * while being absent from the directory: nobody could seal a message to
     * it, so every chat it was in stayed in the clear, silently and for good.
     * This is what lets a later launch notice and retry.
     */
    val publishedAt: Long = 0L,
) {
    // Room warns about arrays in data classes; identity is the row id, not the bytes.
    override fun equals(other: Any?): Boolean = other is OwnIdentityEntity && other.id == id
    override fun hashCode(): Int = id
}

@Entity(tableName = "sessions")
data class SessionEntity(
    /** The remote device's id — one session per device, addressed by that id. */
    @PrimaryKey val address: String,
    val record: ByteArray,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is SessionEntity && other.address == address
    override fun hashCode(): Int = address.hashCode()
}

@Entity(tableName = "prekeys")
data class PreKeyEntity(@PrimaryKey val keyId: Int, val record: ByteArray) {
    override fun equals(other: Any?): Boolean = other is PreKeyEntity && other.keyId == keyId
    override fun hashCode(): Int = keyId
}

@Entity(tableName = "signed_prekeys")
data class SignedPreKeyEntity(@PrimaryKey val keyId: Int, val record: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SignedPreKeyEntity && other.keyId == keyId
    override fun hashCode(): Int = keyId
}

@Entity(tableName = "kyber_prekeys")
data class KyberPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val record: ByteArray,
    /** The last-resort key is reused; one-time Kyber keys would be marked here. */
    val used: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is KyberPreKeyEntity && other.keyId == keyId
    override fun hashCode(): Int = keyId
}

/**
 * Someone else's identity key, as first seen. A change here is what a safety
 * number warning is made of: it means the person reinstalled, changed phone —
 * or that someone is in the middle.
 */
@Entity(tableName = "remote_identities")
data class RemoteIdentityEntity(
    /** The remote device id. */
    @PrimaryKey val address: String,
    val userId: String,
    val identityKey: ByteArray,
    val firstSeenAt: Long,
    /** Set when the key changed after first use, until the person is told. */
    val changedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is RemoteIdentityEntity && other.address == address
    override fun hashCode(): Int = address.hashCode()
}


/**
 * A message this device has already opened, kept until the chat database has
 * the result.
 *
 * Opening a sealed message moves the ratchet on, and the ratchet is saved
 * here, in this database; the readable result is saved in another one. If the
 * app died between the two, the message could never be opened again — the key
 * for it is already gone. So the result is written in the same transaction as
 * the ratchet, and dropped once the chat has it.
 *
 * Plaintext is kept only as long as that handover takes, and only on this
 * phone, which already keeps every opened message in the chat database.
 */
@Entity(tableName = "opened_messages")
data class OpenedMessageEntity(
    @PrimaryKey val messageId: String,
    val plaintext: String,
    val openedAt: Long,
)

/**
 * A sender key: how a group message is encrypted once rather than once per
 * member. Nothing writes these until groups are encrypted, but the protocol
 * store must be able to hold them.
 */
@Entity(tableName = "sender_keys", primaryKeys = ["address", "distributionId"])
data class SenderKeyEntity(
    val address: String,
    val distributionId: String,
    val record: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SenderKeyEntity && other.address == address && other.distributionId == distributionId
    override fun hashCode(): Int = 31 * address.hashCode() + distributionId.hashCode()
}

@Dao
interface E2eeDao {
    @Query("SELECT * FROM sender_keys WHERE address = :address AND distributionId = :distributionId")
    fun senderKey(address: String, distributionId: String): SenderKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSenderKey(row: SenderKeyEntity)

    @Query("DELETE FROM sender_keys")
    fun wipeSenderKeys()

    @Query("SELECT * FROM opened_messages WHERE messageId = :messageId")
    fun openedMessage(messageId: String): OpenedMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveOpenedMessage(row: OpenedMessageEntity)

    @Query("DELETE FROM opened_messages WHERE messageId = :messageId")
    fun deleteOpenedMessage(messageId: String)

    @Query("DELETE FROM opened_messages WHERE openedAt < :before")
    fun deleteOpenedBefore(before: Long)

    @Query("DELETE FROM opened_messages")
    fun wipeOpenedMessages()

    @Query("SELECT * FROM own_identity WHERE id = 1")
    fun ownIdentity(): OwnIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveOwnIdentity(row: OwnIdentityEntity)

    @Query("SELECT * FROM sessions WHERE address = :address")
    fun session(address: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE address IN (:addresses)")
    fun sessions(addresses: List<String>): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSession(row: SessionEntity)

    @Query("DELETE FROM sessions WHERE address = :address")
    fun deleteSession(address: String)

    @Query("SELECT * FROM prekeys WHERE keyId = :keyId")
    fun preKey(keyId: Int): PreKeyEntity?

    /** The lowest-numbered prekeys still held, for re-publishing after a failed upload. */
    @Query("SELECT * FROM prekeys ORDER BY keyId LIMIT :limit")
    fun preKeys(limit: Int): List<PreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePreKey(row: PreKeyEntity)

    @Query("DELETE FROM prekeys WHERE keyId = :keyId")
    fun deletePreKey(keyId: Int)

    @Query("SELECT * FROM signed_prekeys WHERE keyId = :keyId")
    fun signedPreKey(keyId: Int): SignedPreKeyEntity?

    @Query("SELECT * FROM signed_prekeys")
    fun signedPreKeys(): List<SignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSignedPreKey(row: SignedPreKeyEntity)

    @Query("DELETE FROM signed_prekeys WHERE keyId = :keyId")
    fun deleteSignedPreKey(keyId: Int)

    @Query("SELECT * FROM kyber_prekeys WHERE keyId = :keyId")
    fun kyberPreKey(keyId: Int): KyberPreKeyEntity?

    @Query("SELECT * FROM kyber_prekeys")
    fun kyberPreKeys(): List<KyberPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveKyberPreKey(row: KyberPreKeyEntity)

    @Query("UPDATE kyber_prekeys SET used = 1 WHERE keyId = :keyId")
    fun markKyberUsed(keyId: Int)

    @Query("SELECT * FROM remote_identities WHERE address = :address")
    fun remoteIdentity(address: String): RemoteIdentityEntity?

    @Query("SELECT * FROM remote_identities WHERE userId = :userId")
    fun remoteIdentitiesFor(userId: String): List<RemoteIdentityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveRemoteIdentity(row: RemoteIdentityEntity)

    @Query("UPDATE remote_identities SET changedAt = NULL WHERE userId = :userId")
    fun clearIdentityWarning(userId: String)

    @Query("DELETE FROM own_identity")
    fun wipeIdentity()

    @Query("DELETE FROM sessions")
    fun wipeSessions()

    @Query("DELETE FROM prekeys")
    fun wipePreKeys()

    @Query("DELETE FROM signed_prekeys")
    fun wipeSignedPreKeys()

    @Query("DELETE FROM kyber_prekeys")
    fun wipeKyberPreKeys()

    @Query("DELETE FROM remote_identities")
    fun wipeRemoteIdentities()
}

@Database(
    entities = [
        OwnIdentityEntity::class,
        SessionEntity::class,
        PreKeyEntity::class,
        SignedPreKeyEntity::class,
        KyberPreKeyEntity::class,
        RemoteIdentityEntity::class,
        SenderKeyEntity::class,
        OpenedMessageEntity::class,
    ],
    // v3: opened_messages, so a crash between opening and saving loses nothing.
    version = 3,
    exportSchema = false,
)
abstract class E2eeDatabase : RoomDatabase() {
    abstract fun dao(): E2eeDao

    companion object {
        @Volatile private var instance: E2eeDatabase? = null

        /**
         * Adds publishedAt. Every existing install gets 0, which is the honest
         * answer: this database cannot know whether the upload that came with
         * its registration ever reached the server, so the next launch asks.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE own_identity ADD COLUMN publishedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the handover table for opened messages; nothing existing changes. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `opened_messages` (" +
                        "`messageId` TEXT NOT NULL, `plaintext` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`messageId`))",
                )
            }
        }

        fun get(context: Context): E2eeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    E2eeDatabase::class.java,
                    "viro_e2ee.db",
                )
                    // Deliberately no destructive fallback: see the note at the
                    // top of this file. Losing this file loses the chats.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
