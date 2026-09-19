package com.viroreach.app.relationships

import android.content.Context
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.CheckinBody
import com.viroreach.core.network.CommitmentBody
import com.viroreach.core.network.CommitmentDto
import com.viroreach.core.network.CommitmentPatchBody
import com.viroreach.core.network.DateBody
import com.viroreach.core.network.LoopAnswerBody
import com.viroreach.core.network.LoopBody
import com.viroreach.core.network.LoopDto
import com.viroreach.core.network.LoopPatchBody
import com.viroreach.core.network.OverviewDto
import com.viroreach.core.network.RelationshipBody
import com.viroreach.core.network.RelationshipDto
import com.viroreach.core.network.ReminderSettingsDto
import com.viroreach.core.network.TimelineDto
import com.viroreach.core.network.ViroMessagingApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Relationship intelligence on the phone: Targets, Moments, Commitments,
 * Loops and the Connections overview. Private data stays server-side under
 * the owner's account; this caches the latest overview so the inbox, chat
 * header and contact page can show context without each fetching it.
 */
class RelationshipRepository(context: Context, private val api: ViroMessagingApi) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val _overview = MutableStateFlow<OverviewDto?>(null)
    val overview: StateFlow<OverviewDto?> = _overview.asStateFlow()
    private var lastRefresh = 0L

    suspend fun refresh(force: Boolean = false): OverviewDto? = mutex.withLock {
        if (!force && System.currentTimeMillis() - lastRefresh < 20_000) return@withLock _overview.value
        runCatching { api.overview() }
            .onSuccess {
                _overview.value = it
                lastRefresh = System.currentTimeMillis()
                ReminderScheduler.scheduleCommitments(appContext, it.relationships.orEmpty().flatMap { r -> r.openCommitments.orEmpty() })
            }
        _overview.value
    }

    fun relationshipFor(userId: String?, phone: String?): RelationshipDto? =
        _overview.value?.relationships?.firstOrNull {
            (userId != null && it.subjectUserId == userId) || (phone != null && it.subjectPhone == phone)
        }

    private suspend fun <T> call(what: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        val f = ApiDiagnostics.parseFailure("", what, e)
        Result.failure(IllegalStateException(f.message ?: "Couldn't $what. Check your connection."))
    }

    suspend fun save(body: RelationshipBody): Result<String> = call("save") {
        val row = api.upsertRelationship(body)
        refresh(force = true)
        row.id
    }

    suspend fun remove(id: String) = call("remove") {
        api.deleteRelationship(id)
        refresh(force = true)
    }

    suspend fun addDate(relationshipId: String, body: DateBody) = call("add the date") {
        api.addDate(relationshipId, body)
        refresh(force = true)
    }

    suspend fun removeDate(dateId: String) = call("remove the date") {
        api.deleteDate(dateId)
        refresh(force = true)
    }

    suspend fun checkIn(relationshipId: String, note: String?) = call("log the check-in") {
        api.checkIn(relationshipId, CheckinBody(note))
        refresh(force = true)
    }

    suspend fun timeline(relationshipId: String): Result<TimelineDto> = call("load Moments") { api.timeline(relationshipId) }

    // ---------------------------------------------------------- commitments

    suspend fun addCommitment(
        text: String,
        due: LocalDateTime,
        kind: String,
        subjectUserId: String?,
        subjectPhone: String?,
        displayName: String?,
        conversationId: String?,
        messageId: String?,
    ): Result<CommitmentDto> = call("save the commitment") {
        val dto = api.addCommitment(
            CommitmentBody(
                text = text,
                dueAt = due.atZone(ZoneId.systemDefault()).toInstant().toString(),
                kind = kind,
                subjectUserId = subjectUserId,
                subjectPhone = subjectPhone,
                displayName = displayName,
                conversationId = conversationId?.takeUnless { it.startsWith("peer:") },
                messageId = messageId?.takeUnless { it.startsWith("local:") },
            ),
        )
        ReminderScheduler.scheduleCommitments(appContext, listOf(dto))
        refresh(force = true)
        dto
    }

    suspend fun setCommitmentStatus(id: String, status: String) = call("update the commitment") {
        val dto = api.updateCommitment(id, CommitmentPatchBody(status = status))
        if (status != "OPEN") ReminderScheduler.cancelCommitment(appContext, id)
        refresh(force = true)
        dto
    }

    suspend fun snoozeCommitment(id: String, until: Instant) = call("snooze") {
        val dto = api.updateCommitment(id, CommitmentPatchBody(dueAt = until.toString()))
        ReminderScheduler.scheduleCommitments(appContext, listOf(dto))
        dto
    }

    suspend fun openCommitments(): List<CommitmentDto> = runCatching { api.commitments("OPEN") }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- loops

    suspend fun loops(conversationId: String): List<LoopDto> =
        if (conversationId.startsWith("peer:")) emptyList()
        else runCatching { api.loops(conversationId) }.getOrDefault(emptyList())

    suspend fun myLoops(): List<LoopDto> = runCatching { api.loops(null) }.getOrDefault(emptyList())

    suspend fun createLoop(body: LoopBody): Result<LoopDto> = call("start the Loop") {
        val loop = api.createLoop(body.copy(timezone = TimeZone.getDefault().id))
        ReminderScheduler.scheduleLoopReminders(appContext)
        loop
    }

    suspend fun answerLoop(loopId: String, body: LoopAnswerBody): Result<LoopDto> = call("send your answer") {
        val loop = api.answerLoop(loopId, body)
        refresh(force = true)
        loop
    }

    suspend fun setLoopActive(loopId: String, active: Boolean) = call("update the Loop") {
        api.updateLoop(loopId, LoopPatchBody(active = active))
    }

    suspend fun deleteLoop(loopId: String) = call("delete the Loop") { api.deleteLoop(loopId) }

    suspend fun loopHistory(loopId: String) = call("load the Loop") { api.loopHistory(loopId) }

    // ------------------------------------------------------------- settings

    suspend fun reminderSettings(): ReminderSettingsDto? = runCatching { api.reminderSettings() }.getOrNull()

    suspend fun saveReminderSettings(s: ReminderSettingsDto) = call("save settings") {
        val saved = api.updateReminderSettings(s.copy(timezone = TimeZone.getDefault().id))
        ReminderScheduler.scheduleBrief(appContext, saved.briefTime ?: "08:00", saved.briefEnabled != false)
        saved
    }
}
