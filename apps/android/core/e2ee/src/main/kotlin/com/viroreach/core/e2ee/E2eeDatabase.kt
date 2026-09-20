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

@Dao
interface E2eeDao {
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
    ],
    version = 1,
    exportSchema = false,
)
abstract class E2eeDatabase : RoomDatabase() {
    abstract fun dao(): E2eeDao

    companion object {
        @Volatile private var instance: E2eeDatabase? = null

        fun get(context: Context): E2eeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    E2eeDatabase::class.java,
                    "viro_e2ee.db",
                )
                    // Deliberately no destructive fallback: see the note at the
                    // top of this file. Losing this file loses the chats.
                    .build()
                    .also { instance = it }
            }
    }
}
