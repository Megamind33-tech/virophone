package com.viroreach.core.model

enum class ContactRelationshipState {
    PHONE_CONTACT,
    VIRO_CONNECTION,
    PHONE_CONTACT_AND_CONNECTION,
    BLOCKED,
    UNKNOWN
}

enum class PresenceState {
    OFFLINE,
    VIRO_ONLINE,
    LOCAL_NETWORK,
    WIFI_DIRECT,
    VIRO_MESH,
    VIRO_RADIO
}

enum class ReachabilityState {
    UNREACHABLE,
    REACHABLE
}

enum class CallStateMachineState {
    IDLE,
    RESOLVING_CONTACT,
    SELECTING_ROUTE,
    AUTHORIZING,
    SIGNALING,
    CONNECTING,
    RINGING,
    ACTIVE,
    RECONNECTING,
    ENDING,
    ENDED,
    FAILED,
    UNAUTHORIZED,
    UNREACHABLE,
    NETWORK_FAILED,
    PEER_REJECTED,
    BUSY,
    TIMEOUT,
    MEDIA_FAILED,
    SERVER_FAILED
}

enum class CallRouteType {
    LAN,
    WIFI_DIRECT,
    INTERNET_P2P,
    TURN_RELAY,
    VIRO_MESH,
    VIRO_RADIO
}

enum class TransportAvailability {
    AVAILABLE,
    UNAVAILABLE
}

data class KnownContact(
    val userId: String,
    val localName: String,
    val phoneE164: String?,
    val viroId: String?,
    val relationshipState: ContactRelationshipState,
    val presence: PresenceState = PresenceState.OFFLINE,
    val reachability: ReachabilityState = ReachabilityState.UNREACHABLE,
    val preferredTransport: CallRouteType? = null
)

data class LocalDiscoveryAdvertisement(
    val protocol: String = "viro-reach",
    val version: String = "1",
    val ephemeralId: String,
    val capabilities: List<String> = listOf("voice")
)

data class AnonymousPeer(
    val ephemeralId: String,
    val capabilities: List<String>,
    val transportType: CallRouteType
)

data class AuthorizedNearbyContact(
    val contact: KnownContact,
    val ephemeralId: String,
    val transportType: CallRouteType
)

data class PublicProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val viroId: String
)

data class ContactDiscoveryMatch(
    val phoneE164: String,
    val userId: String,
    val viroId: String,
    val displayName: String,
    val avatarUrl: String?,
    val relationshipState: ContactRelationshipState
)
