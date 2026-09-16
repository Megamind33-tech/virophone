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
) : ViroApiService {
    private fun err(): Nothing = error("stub")
    override suspend fun discoverContacts(body: DiscoverBody) = DiscoverResponse(matches)
    override suspend fun requestOtp(body: OtpRequestBody) = err()
    override suspend fun verifyOtp(body: OtpVerifyBody) = err()
    override suspend fun refreshToken(body: RefreshBody) = err()
    override suspend fun getMe() = err()
    override suspend fun updateMe(body: UpdateMeBody) = err()
    override suspend fun exportAccount() = err()
    override suspend fun deleteAccount() = err()
    override suspend fun listBlocks() = err()
    override suspend fun exactViroIdLookup(viroId: String) = err()
    override suspend fun authorizeCall(body: AuthorizeCallBody) = err()
    override suspend fun endCall(callId: String) = err()
    override suspend fun registerEphemeral(body: RegisterEphemeralBody) = err()
    override suspend fun resolveEphemeral(body: ResolveEphemeralBody) = err()
    override suspend fun getTurnCredentials() = err()
    override suspend fun getOfflineTrustMaterial() = err()
    override suspend fun getOfflineCallTickets() = err()
    override suspend fun blockUser(body: BlockUserBody) = err()
    override suspend fun unblockUser(userId: String) = err()
    override suspend fun inviteConnection(body: ConnectionInviteBody) = err()
    override suspend fun listConnections() = err()
    override suspend fun acceptConnection(id: String) = err()
    override suspend fun rejectConnection(id: String) = err()
    override suspend fun revokeConnection(id: String) = err()
    override suspend fun listDevices() = err()
    override suspend fun revokeDevice(id: String) = err()
    override suspend fun listPlans() = err()
    override suspend fun getMySubscription() = err()
    override suspend fun selectSubscription(body: SelectPlanBody) = err()
    override suspend fun registerPushToken(body: RegisterPushTokenBody) = err()
    override suspend fun removePushToken(body: RemovePushTokenBody) = err()
    override suspend fun getCallHistory() = err()
    override suspend fun postCallQuality(callId: String, body: CallQualityBody) = err()
    override suspend fun sendMessage(body: SendMessageBody) = err()
    override suspend fun listConversations() = err()
    override suspend fun conversationHistory(id: String) = err()
    override suspend fun markConversationRead(id: String) = err()
    override suspend fun createConference(body: CreateConferenceBody) = err()
    override suspend fun conferenceParticipants(id: String) = err()
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
