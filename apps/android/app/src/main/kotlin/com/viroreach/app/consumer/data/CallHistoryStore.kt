package com.viroreach.app.consumer.data



import android.content.Context

import androidx.datastore.preferences.core.edit

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

import androidx.datastore.preferences.preferencesDataStore

import com.viroreach.app.consumer.resolveCallLogType
import com.viroreach.core.model.CallStateMachineState

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.first

import kotlinx.coroutines.launch

import org.json.JSONArray

import org.json.JSONObject

import java.time.LocalDate

import java.time.format.DateTimeFormatter

import java.util.Locale

import java.util.UUID



enum class CallLogType {

    OUTGOING, INCOMING, MISSED, DECLINED, FAILED, COMPLETED, GROUP, VOICEMAIL,

}



data class CallLogEntry(

    val id: String,

    val name: String,

    val phoneE164: String?,

    val type: CallLogType,

    val timestampMs: Long,

    val durationSeconds: Int,

    val answerTimestampMs: Long? = null,

    val endTimestampMs: Long? = null,

    /** Known for server-synced history even when no phone number is on file. */
    val peerUserId: String? = null,

)



private val Context.callHistoryDataStore by preferencesDataStore("viro_call_history")



class CallHistoryStore(context: Context) {

    private val store = context.applicationContext.callHistoryDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)



    private val _entries = MutableStateFlow<List<CallLogEntry>>(emptyList())

    val entries: StateFlow<List<CallLogEntry>> = _entries.asStateFlow()



    /**
     * When the call list was last looked at.
     *
     * A missed call has no seen flag of its own, and adding one to every
     * entry would mean rewriting the whole history just to clear a badge.
     * One instant answers the only question being asked — what came in since
     * the last look — and survives a restart, which a count held in memory
     * would not.
     */
    private val _seenAt = MutableStateFlow(0L)

    val seenAt: StateFlow<Long> = _seenAt.asStateFlow()

    /** The call list is on screen: nothing in it is news any more. */
    fun markSeen() {
        val now = System.currentTimeMillis()
        _seenAt.value = now
        scope.launch { store.edit { prefs -> prefs[KEY_SEEN_AT] = now } }
    }

    private var activeLogId: String? = null

    fun hasActiveCall(): Boolean = activeLogId != null



    init {

        scope.launch { loadPersisted() }

    }



    private suspend fun loadPersisted() {

        _seenAt.value = store.data.first()[KEY_SEEN_AT] ?: 0L

        val raw = store.data.first()[KEY_ENTRIES].orEmpty()

        if (raw.isBlank()) return

        runCatching {

            val arr = JSONArray(raw)

            val list = buildList {

                for (i in 0 until arr.length()) {

                    add(arr.getJSONObject(i).toEntry())

                }

            }

            _entries.value = list

        }

    }



    private suspend fun persist() {

        val arr = JSONArray()

        _entries.value.forEach { arr.put(it.toJson()) }

        store.edit { prefs -> prefs[KEY_ENTRIES] = arr.toString() }

    }



    fun add(entry: CallLogEntry) {

        _entries.value = listOf(entry) + _entries.value

        scope.launch { persist() }

    }

    fun deleteEntry(id: String) {
        _entries.value = _entries.value.filter { it.id != id }
        scope.launch { persist() }
    }

    fun clearAll() {
        _entries.value = emptyList()
        scope.launch { persist() }
    }

    /**
     * Merges server call history into the local log. Server rows win on id
     * collision; local-only entries (in-progress / never synced) are kept.
     */
    fun mergeServerHistory(serverEntries: List<CallLogEntry>) {
        if (serverEntries.isEmpty()) return
        val byId = LinkedHashMap<String, CallLogEntry>()
        _entries.value.forEach { byId[it.id] = it }
        serverEntries.forEach { remote ->
            val local = byId[remote.id]
            byId[remote.id] = if (local == null) {
                remote
            } else {
                remote.copy(
                    name = remote.name.ifBlank { local.name },
                    phoneE164 = remote.phoneE164 ?: local.phoneE164,
                    durationSeconds = maxOf(remote.durationSeconds, local.durationSeconds),
                )
            }
        }
        _entries.value = byId.values.sortedByDescending { it.timestampMs }
        scope.launch { persist() }
    }

    fun historyForPhone(phoneE164: String): List<CallLogEntry> {
        val normalized = phoneE164.trim()
        return _entries.value.filter { it.phoneE164 == normalized }
    }



    fun onCallStarted(name: String, phoneE164: String?, outgoing: Boolean): String {

        val id = UUID.randomUUID().toString()

        activeLogId = id

        add(

            CallLogEntry(

                id = id,

                name = name,

                phoneE164 = phoneE164,

                type = if (outgoing) CallLogType.OUTGOING else CallLogType.INCOMING,

                timestampMs = System.currentTimeMillis(),

                durationSeconds = 0,

            ),

        )

        return id

    }



    fun onCallAnswered() {

        val id = activeLogId ?: return

        updateEntry(id) { it.copy(answerTimestampMs = System.currentTimeMillis()) }

    }



    fun onCallEnded(state: CallStateMachineState, durationSeconds: Int) {
        val id = activeLogId ?: return
        val current = _entries.value.find { it.id == id }
        val type = resolveCallLogType(state, durationSeconds, current?.answerTimestampMs, current?.type)
        updateEntry(id) {
            it.copy(
                type = type,
                durationSeconds = durationSeconds,
                endTimestampMs = System.currentTimeMillis(),
            )
        }
        activeLogId = null
    }



    private fun updateEntry(id: String, transform: (CallLogEntry) -> CallLogEntry) {

        _entries.value = _entries.value.map { if (it.id == id) transform(it) else it }

        scope.launch { persist() }

    }



    fun groupedByDate(): List<Pair<String, List<CallLogEntry>>> {

        val today = LocalDate.now()

        val yesterday = today.minusDays(1)

        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        return _entries.value.groupBy { entry ->

            val date = java.time.Instant.ofEpochMilli(entry.timestampMs)

                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

            when (date) {

                today -> "Today"

                yesterday -> "Yesterday"

                else -> date.format(fmt)

            }

        }.toList()

    }



    companion object {

        private val KEY_ENTRIES = stringPreferencesKey("entries")

        /** When the call list was last looked at, so a missed call stops being news. */
        private val KEY_SEEN_AT = longPreferencesKey("seen_at")

    }

}



private fun CallLogEntry.toJson(): JSONObject = JSONObject().apply {

    put("id", id)

    put("name", name)

    put("phoneE164", phoneE164)

    put("type", type.name)

    put("timestampMs", timestampMs)

    put("durationSeconds", durationSeconds)

    put("answerTimestampMs", answerTimestampMs ?: JSONObject.NULL)

    put("endTimestampMs", endTimestampMs ?: JSONObject.NULL)

}



private fun JSONObject.toEntry(): CallLogEntry = CallLogEntry(

    id = getString("id"),

    name = getString("name"),

    phoneE164 = optString("phoneE164").ifBlank { null },

    type = CallLogType.valueOf(getString("type")),

    timestampMs = getLong("timestampMs"),

    durationSeconds = getInt("durationSeconds"),

    answerTimestampMs = optLong("answerTimestampMs").takeIf { it > 0L },

    endTimestampMs = optLong("endTimestampMs").takeIf { it > 0L },

)


