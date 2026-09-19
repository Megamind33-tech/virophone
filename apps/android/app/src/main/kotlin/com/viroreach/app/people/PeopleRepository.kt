package com.viroreach.app.people

import android.content.Context
import android.util.Log
import com.viroreach.app.data.ViroDatabaseProvider
import com.viroreach.core.database.KnownContactEntity
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.ConnectionDto
import com.viroreach.core.network.ConnectionInviteBody
import com.viroreach.core.network.FoundPerson
import com.viroreach.core.network.MeResponse
import com.viroreach.core.network.UpdateMeBody
import com.viroreach.core.network.ViroApiService
import com.viroreach.core.network.ViroIdCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Your own profile (name, Viro ID, email discovery) and the people you reach
 * without a phone number: find by Viro ID or email, connection requests, and
 * accepted connections as real entries in Contacts.
 */
class PeopleRepository(context: Context, private val api: () -> ViroApiService) {
    private val dao = ViroDatabaseProvider.get(context.applicationContext).knownContactDao()
    private val syncLock = Mutex()

    private val _me = MutableStateFlow<MeResponse?>(null)
    val me: StateFlow<MeResponse?> = _me.asStateFlow()

    private val _connections = MutableStateFlow<List<ConnectionDto>>(emptyList())
    val connections: StateFlow<List<ConnectionDto>> = _connections.asStateFlow()

    /** Requests waiting for my answer. */
    val incomingCount: Int get() = _connections.value.count { it.isIncomingRequest }

    suspend fun refreshMe(): MeResponse? = runCatching { api().getMe() }
        .onSuccess { _me.value = it }
        .onFailure { Log.w(TAG, "ME_FAILED ${it.message}") }
        .getOrNull()

    /** True when the one-time "name + Viro ID" step still needs doing. Offline or an old server: false. */
    suspend fun needsProfileSetup(): Boolean = refreshMe()?.profileCompleted == false

    suspend fun checkViroId(id: String?, name: String?): ViroIdCheck? =
        runCatching { api().checkViroId(id?.takeIf { it.isNotBlank() }, name?.takeIf { it.isNotBlank() }) }.getOrNull()

    suspend fun completeProfile(name: String, viroId: String): Result<MeResponse> = call {
        api().updateMe(UpdateMeBody(displayName = name.trim(), viroId = viroId.trim(), completeProfile = true))
    }.onSuccess { _me.value = it }

    suspend fun update(body: UpdateMeBody): Result<MeResponse> = call { api().updateMe(body) }.onSuccess { _me.value = it }

    suspend fun find(query: String): Result<FoundPerson?> = call { api().findPerson(query.trim()).person }

    suspend fun connect(userId: String): Result<ConnectionDto> = call { api().inviteConnection(ConnectionInviteBody(userId)) }
        .onSuccess { refreshConnections() }

    suspend fun accept(connectionId: String): Result<ConnectionDto> = call { api().acceptConnection(connectionId) }
        .onSuccess { refreshConnections() }

    suspend fun decline(connectionId: String): Result<ConnectionDto> = call { api().rejectConnection(connectionId) }
        .onSuccess { refreshConnections() }

    /** Removes a connection, or cancels my pending request. */
    suspend fun remove(connectionId: String): Result<Unit> = call { api().revokeConnection(connectionId) }
        .onSuccess { refreshConnections() }

    /**
     * Reloads connections and mirrors accepted ones into Contacts: a person with
     * no phone number (or whose number isn't in my address book) appears as a
     * Viro contact with Message and Call. Someone already in Contacts through
     * their phone number isn't duplicated.
     */
    suspend fun refreshConnections(): List<ConnectionDto> = syncLock.withLock {
        val list = runCatching { api().listConnections() }
            .onFailure { Log.w(TAG, "CONNECTIONS_FAILED ${it.message}") }
            .getOrNull() ?: return@withLock _connections.value
        _connections.value = list
        runCatching { mirrorIntoContacts(list) }.onFailure { Log.w(TAG, "CONNECTIONS_MIRROR_FAILED ${it.message}") }
        list
    }

    private suspend fun mirrorIntoContacts(list: List<ConnectionDto>) {
        val accepted = list.filter { it.status == "ACCEPTED" && !it.peer.isNullOrBlank() }
        val all = dao.getAll()
        val viaPhone = all.filter { !it.id.startsWith(VIRO_ROW) && it.userId != null }.mapNotNull { it.userId }.toSet()
        val existing = all.filter { it.id.startsWith(VIRO_ROW) }.associateBy { it.id }
        val now = System.currentTimeMillis()
        val wanted = accepted.filter { it.peer!! !in viaPhone }.map { c ->
            val id = VIRO_ROW + c.peer
            val prev = existing[id]
            KnownContactEntity(
                id = id,
                displayName = c.peerDisplayName?.takeIf { it.isNotBlank() } ?: c.peerViroId ?: "Viro contact",
                phoneE164 = null,
                userId = c.peer,
                viroProfilePhotoUrl = c.peerAvatarUrl,
                localPhotoUri = null,
                customDisplayName = prev?.customDisplayName,
                customPhotoUri = prev?.customPhotoUri,
                isReachable = true,
                isFavorite = prev?.isFavorite == true,
                isBlocked = prev?.isBlocked == true,
                isSpam = prev?.isSpam == true,
                updatedAt = now,
            )
        }
        if (wanted.isNotEmpty()) dao.upsertAll(wanted)
        val stale = existing.keys - wanted.map { it.id }.toSet()
        if (stale.isNotEmpty()) dao.deleteByIds(stale.toList())
    }

    /** The server's own sentence when it sent one ("That Viro ID is taken."), else something plain. */
    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        val failure = ApiDiagnostics.parseFailure("", "", e)
        val message = failure.message?.takeIf { it.isNotBlank() }
            ?: if (failure.httpStatus > 0) "Something went wrong (${failure.httpStatus})."
            else "No connection. Check your internet and try again."
        Log.w(TAG, "CALL_FAILED ${failure.summary()}")
        Result.failure(IllegalStateException(message))
    }

    companion object {
        private const val TAG = "ViroPeople"
        /** Contacts rows that come from a connection rather than the address book. */
        const val VIRO_ROW = "viro:"
    }
}

private val ConnectionDto.peer: String?
    get() = peerUserId ?: if (direction == "OUTGOING") recipientUserId else requesterUserId

val ConnectionDto.isIncomingRequest: Boolean
    get() = status == "PENDING" && direction == "INCOMING"
