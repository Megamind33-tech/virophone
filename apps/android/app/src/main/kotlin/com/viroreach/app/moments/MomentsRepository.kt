package com.viroreach.app.moments

import com.viroreach.core.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

fun MomentDto.endsAt(): Long = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrDefault(0)
fun MomentDto.activity(): String = text?.takeIf { it.isNotBlank() } ?: when (type) {
    "FREE" -> "Free for a quick call"
    "BREAK" -> "Taking a break"
    "LISTENING" -> "Listening"
    "WATCHING" -> "Watching"
    "GAMING" -> "Gaming"
    "WORKING" -> "Working"
    else -> "A Moment"
}
fun remainingMinutes(end: Long, now: Long): Int = ((end - now).coerceAtLeast(0) + 59_999L).div(60_000L).toInt()

/** Process/session cache: tab switches never blank it; activity is not left on
 * disk after logout. The existing websocket only invalidates; HTTP authorizes. */
class MomentsRepository(private val api: ViroMomentsApi, private val userId: () -> String?) {
    private val mutex = Mutex()
    private var owner: String? = null
    private var offset = 0L
    private val _moments = MutableStateFlow<List<MomentDto>>(emptyList())
    val moments = _moments.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    fun now() = System.currentTimeMillis() + offset
    fun clear() { owner = null; _moments.value = emptyList(); _loaded.value = false; _error.value = null; offset = 0 }
    fun prune() {
        if (owner != userId()) clear()
        _moments.value = _moments.value.filter { it.endsAt() > now() }
    }
    suspend fun refresh() = mutex.withLock {
        val account = userId() ?: return@withLock clear()
        if (owner != account) { clear(); owner = account }
        try {
            val result = api.now()
            if (account != userId()) return@withLock clear()
            offset = Instant.parse(result.serverTime).toEpochMilli() - System.currentTimeMillis()
            _moments.value = result.moments.filter { it.endsAt() > now() }
            _loaded.value = true
            _error.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { prune(); _error.value = "Couldn't refresh Moments. Check your connection." }
    }
    private fun cache(moment: MomentDto) {
        _moments.value = listOf(moment) + _moments.value.filter { it.id != moment.id }
        _loaded.value = true
    }
    suspend fun create(body: CreateMomentBody): Result<MomentDto> = action(onSuccess = ::cache) { api.create(body) }
    suspend fun extend(id: String): Result<MomentDto> = action(onSuccess = ::cache) { api.extend(id, ExtendMomentBody(15)) }
    suspend fun end(id: String): Result<Unit> = action(onSuccess = {
        _moments.value = _moments.value.filter { it.id != id }
    }) { api.end(id) }
    suspend fun verify(id: String): Result<MomentDto> = action { api.get(id) }
    private suspend fun <T> action(onSuccess: (T) -> Unit = {}, block: suspend () -> T): Result<T> {
        val account = userId() ?: return Result.failure(IllegalStateException("Sign in to use Moments."))
        return try {
            val result = block()
            if (account != userId()) return Result.failure(IllegalStateException("Your account changed. Please try again."))
            if (owner != account) { clear(); owner = account }
            // The mutation response is already authoritative. A failed follow-up
            // GET must not hide a newly created Moment or resurrect one just ended.
            onSuccess(result)
            refresh()
            Result.success(result)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            refresh()
            Result.failure(IllegalStateException(when (momentHttpStatus(e)) {
                404 -> "This Moment is no longer available."
                409 -> "You already have an active Moment. Open it to manage it."
                else -> "Couldn't update the Moment. Please try again."
            }))
        }
    }
}
