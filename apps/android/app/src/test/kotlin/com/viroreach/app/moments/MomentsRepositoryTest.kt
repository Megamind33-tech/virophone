package com.viroreach.app.moments

import com.viroreach.core.network.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MomentsRepositoryTest {
    private fun moment(end: Long = System.currentTimeMillis() + 60_000) = MomentDto(
        "moment", "alice", "FREE", null, "CONNECTIONS", "Alice", null,
        Instant.now().toString(), Instant.ofEpochMilli(end).toString(), true,
    )
    private class Api(var list: List<MomentDto>) : ViroMomentsApi {
        var fail = false
        var afterMutation: () -> Unit = {}
        override suspend fun now(): MomentsNowDto {
            if (fail) error("offline")
            return MomentsNowDto(Instant.now().toString(), list)
        }
        override suspend fun get(id: String) = list.first { it.id == id }
        override suspend fun create(body: CreateMomentBody): MomentDto {
            val created = list.first()
            afterMutation()
            return created
        }
        override suspend fun extend(id: String, body: ExtendMomentBody) = error("unused")
        override suspend fun end(id: String) { list = emptyList(); afterMutation() }
    }
    @Test fun `expired cached entries never return`() = runTest {
        val api = Api(listOf(moment(System.currentTimeMillis()-1000)))
        val repo = MomentsRepository(api) { "bob" }
        repo.refresh(); assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `failed refresh preserves still active session cache`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "bob" }
        repo.refresh(); api.fail = true; repo.refresh()
        assertEquals(1,repo.moments.value.size); assertNotNull(repo.error.value)
    }
    @Test fun `account change cannot reuse another accounts cache`() = runTest {
        var user = "bob"; val api = Api(listOf(moment())); val repo = MomentsRepository(api) { user }
        repo.refresh(); user = "stranger"; api.fail = true; repo.refresh()
        assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `authoritative removal clears ended or blocked moments`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "bob" }
        repo.refresh(); api.list = emptyList(); repo.refresh(); assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `create response remains visible when the follow up refresh fails`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "alice" }
        api.afterMutation = { api.fail = true }
        assertTrue(repo.create(CreateMomentBody("FREE", null, "CONNECTIONS", 15)).isSuccess)
        assertEquals(1, repo.moments.value.size)
    }
    @Test fun `ending a Moment removes it even when the follow up refresh fails`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "alice" }
        repo.refresh(); api.afterMutation = { api.fail = true }
        assertTrue(repo.end("moment").isSuccess)
        assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `mutation response from an old account is discarded`() = runTest {
        var user = "alice"
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { user }
        api.afterMutation = { user = "bob" }
        assertTrue(repo.create(CreateMomentBody("FREE", null, "CONNECTIONS", 15)).isFailure)
        assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `countdown rounds up but never goes negative`() {
        assertEquals(1,remainingMinutes(1001,1000)); assertEquals(0,remainingMinutes(999,1000))
        assertEquals(2,remainingMinutes(61001,1000))
    }
    @Test fun `free activity has consumer label`() { assertEquals("Free for a quick call", moment().activity()) }
}
