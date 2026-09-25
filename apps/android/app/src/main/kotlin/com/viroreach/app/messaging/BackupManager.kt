package com.viroreach.app.messaging

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.reflect.TypeToken
import com.viroreach.core.database.ConversationEntity
import com.viroreach.core.database.MessageEntity
import com.viroreach.core.database.MessagingDatabase
import com.viroreach.core.e2ee.RecoveryKey
import com.viroreach.core.network.BackupStatusDto
import com.viroreach.core.network.ViroBackupApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What is in an archive: this phone's chats, as it holds them. */
private data class BackupArchive(
    val v: Int = 1,
    val createdAt: Long,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
)

/** What the backup screen shows. */
data class BackupState(
    val on: Boolean,
    val exists: Boolean,
    val sizeBytes: Long,
    val messageCount: Int,
    val updatedAt: String?,
)

/**
 * The encrypted backup of this phone's chats.
 *
 * End-to-end encryption means a message is opened once, by the device it was
 * sent to — so the copy on this phone is the only one there will ever be, and
 * a reinstall would lose every conversation. This is what stops that: the
 * whole message store is encrypted here, under a recovery key that never
 * leaves the phone, and the result is handed to a server that cannot open it.
 *
 * Losing the recovery key loses the history. Nobody can undo that, and the
 * screen that shows the key says so before it is ever needed.
 */
class BackupManager(
    context: Context,
    private val api: ViroBackupApi,
    private val http: OkHttpClient,
    private val baseUrl: String,
) {
    private val appContext = context.applicationContext
    // Looked up each time: it is the signed-in account's store, and that can change.
    private val dao get() = MessagingDatabase.get(appContext).dao()

    /**
     * The recovery key is kept here so backups can run without asking for it
     * every night. It is the key to everything, so it goes in the encrypted
     * store rather than ordinary preferences.
     */
    private val prefs by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                appContext,
                "viro_backup",
                MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    /** True once the person has turned backup on and holds a key. */
    fun isOn(): Boolean = storedKey() != null

    private fun storedKey(): ByteArray? =
        prefs?.getString(KEY_RECOVERY, null)?.let { RecoveryKey.decode(it) }

    /**
     * Turns backup on with a fresh key, which the caller must show the person
     * once and never again — this phone keeps only the bytes.
     */
    fun turnOn(): String {
        val shown = RecoveryKey.generate()
        val bytes = RecoveryKey.parse(shown) ?: error("generated an unreadable key")
        prefs?.edit()?.putString(KEY_RECOVERY, RecoveryKey.encode(bytes))?.apply()
        return shown
    }

    /** Turns it off and throws away what the server is holding. */
    suspend fun turnOff(): Boolean = withContext(Dispatchers.IO) {
        prefs?.edit()?.remove(KEY_RECOVERY)?.apply()
        runCatching { api.remove() }.isSuccess
    }

    /** Where things stand, for the backup screen. */
    suspend fun state(): BackupState = withContext(Dispatchers.IO) {
        val status = runCatching { api.status() }.getOrNull() ?: BackupStatusDto()
        BackupState(
            on = isOn(),
            exists = status.exists == true,
            sizeBytes = status.sizeBytes ?: 0,
            messageCount = status.messageCount ?: 0,
            updatedAt = status.updatedAt,
        )
    }

    /**
     * Writes a new backup, replacing the last one.
     *
     * Media is deliberately not in here: a sealed file is already on the
     * server, and the key to it comes back with the message that carried it.
     */
    suspend fun backupNow(): Result<BackupState> = withContext(Dispatchers.IO) {
        runCatching {
            val key = storedKey() ?: error("Backup is not switched on")
            val conversations = dao.allConversations()
            val messages = dao.allMessages(MAX_MESSAGES)
            val json = ChatJson.gson.toJson(
                BackupArchive(
                    createdAt = System.currentTimeMillis(),
                    conversations = conversations,
                    messages = messages,
                ),
            )
            val packed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()
            val archive = MAGIC + RecoveryKey.seal(packed, key)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "viro.backup",
                    archive.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
                )
                .addFormDataPart("messageCount", messages.size.toString())
                .addFormDataPart("conversationCount", conversations.size.toString())
                .addFormDataPart("version", VERSION.toString())
                .build()
            val request = Request.Builder().url("${baseUrl}api/v1/backup").post(body).build()
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("Backup failed (${res.code})")
            }
            Log.i(TAG, "BACKUP_WRITTEN messages=${messages.size} bytes=${archive.size}")
            state()
        }
    }

    /**
     * Puts a backup back on this phone.
     *
     * Anything already here is kept: a restore adds what the backup holds
     * rather than replacing a conversation someone has already started.
     */
    suspend fun restore(typedKey: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val key = RecoveryKey.parse(typedKey)
                ?: throw IllegalArgumentException("That does not look like a recovery key")
            val request = Request.Builder().url("${baseUrl}api/v1/backup/archive").get().build()
            val archive = http.newCall(request).execute().use { res ->
                if (res.code == 404) throw IllegalStateException("There is no backup to restore")
                if (!res.isSuccessful) throw IllegalStateException("Could not fetch the backup (${res.code})")
                res.body?.bytes() ?: throw IllegalStateException("The backup was empty")
            }
            if (archive.size <= MAGIC.size || !archive.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw IllegalStateException("That backup was not written by Viro")
            }
            val packed = RecoveryKey.open(archive.copyOfRange(MAGIC.size, archive.size), key)
                ?: throw IllegalArgumentException("That key does not open this backup")
            val json = GZIPInputStream(packed.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
            val restored = ChatJson.gson.fromJson<BackupArchive>(json, archiveType)
                ?: throw IllegalStateException("That backup could not be read")

            if (restored.conversations.isNotEmpty()) dao.upsertConversations(restored.conversations)
            // In batches: a long history is a lot of rows for one statement.
            restored.messages.chunked(400).forEach { dao.upsertMessages(it) }
            // Anything still in the outbox of the old phone is not this phone's
            // to send: it already went, or it never will.
            dao.outbox().forEach { row -> dao.upsertMessages(listOf(row.copy(status = "FAILED"))) }
            Log.i(TAG, "BACKUP_RESTORED messages=${restored.messages.size}")
            restored.messages.size
        }
    }

    companion object {
        private const val TAG = "ViroBackup"
        private const val KEY_RECOVERY = "recovery_key"
        private const val VERSION = 1
        /** Bounded because the whole lot is held in memory while it is written. */
        private const val MAX_MESSAGES = 50_000
        /** What every Viro archive starts with, so the wrong file is refused early. */
        private val MAGIC = "VIROBAK1".toByteArray(Charsets.UTF_8)
        private val archiveType = object : TypeToken<BackupArchive>() {}.type
    }
}
