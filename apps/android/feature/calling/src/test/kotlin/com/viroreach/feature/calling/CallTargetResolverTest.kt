package com.viroreach.feature.calling

import com.viroreach.core.model.ContactDiscoveryMatch
import com.viroreach.core.model.ContactRelationshipState
import com.viroreach.core.network.*
import com.viroreach.core.network.ViroApiService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class DiscoverOnlyApi(
    private val matches: List<ContactDiscoveryMatch>,
) : ViroApiService by (java.lang.reflect.Proxy.newProxyInstance(
    ViroApiService::class.java.classLoader, arrayOf(ViroApiService::class.java),
) { _, method, _ -> error("Unexpected API call: " + method.name) } as ViroApiService) {
    override suspend fun discoverContacts(body: DiscoverBody) = DiscoverResponse(matches)
}

class CallTargetResolverTest {
    @Test
    fun `accepts direct user UUID`() = runTest {
        val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val resolver = CallTargetResolver(DiscoverOnlyApi(emptyList()))
        val result = resolver.resolveTarget(uuid)
        assertEquals(uuid, result.userId)
        assertEquals(uuid, result.inputIdentity)
    }

    @Test(expected = CallTargetException::class)
    fun `rejects invalid phone`() = runTest {
        CallTargetResolver(DiscoverOnlyApi(emptyList())).resolveTarget("not-a-phone")
    }

    @Test
    fun `resolves registered phone to user id`() = runTest {
        val api = DiscoverOnlyApi(
            listOf(
                ContactDiscoveryMatch(
                    phoneE164 = "+260977426940",
                    userId = "user-b-id",
                    viroId = "",
                    displayName = "Phone B",
                    avatarUrl = null,
                    relationshipState = ContactRelationshipState.PHONE_CONTACT,
                ),
            ),
        )
        val result = CallTargetResolver(api).resolveTarget("+260977426940")
        assertEquals("user-b-id", result.userId)
        assertEquals("+260977426940", result.inputIdentity)
    }
}
