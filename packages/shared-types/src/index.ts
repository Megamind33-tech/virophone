// User & Identity
export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED';
export type PhoneIdentityStatus = 'PENDING' | 'VERIFIED' | 'REVOKED';
export type DevicePlatform = 'ANDROID' | 'IOS' | 'WEB';
export type DeviceIntegrityStatus = 'UNKNOWN' | 'PASSED' | 'FAILED' | 'SKIPPED';

// Relationship states
export type ContactRelationshipState =
  | 'PHONE_CONTACT'
  | 'VIRO_CONNECTION'
  | 'PHONE_CONTACT_AND_CONNECTION'
  | 'BLOCKED'
  | 'UNKNOWN';

// Presence
export type PresenceState =
  | 'OFFLINE'
  | 'VIRO_ONLINE'
  | 'LOCAL_NETWORK'
  | 'WIFI_DIRECT'
  | 'VIRO_MESH'
  | 'VIRO_RADIO';

export type ReachabilityState = 'UNREACHABLE' | 'REACHABLE';

// Connection status
export type ConnectionStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'REVOKED';

// Call
export type CallStatus =
  | 'INITIATED'
  | 'RINGING'
  | 'ACTIVE'
  | 'ENDED'
  | 'FAILED'
  | 'REJECTED'
  | 'BUSY'
  | 'TIMEOUT';

export type CallRouteType =
  | 'LAN'
  | 'WIFI_DIRECT'
  | 'INTERNET_P2P'
  | 'TURN_RELAY'
  | 'VIRO_MESH'
  | 'VIRO_RADIO';

export type CallStateMachineState =
  | 'IDLE'
  | 'RESOLVING_CONTACT'
  | 'SELECTING_ROUTE'
  | 'AUTHORIZING'
  | 'CONNECTING'
  | 'RINGING'
  | 'ACTIVE'
  | 'ENDING'
  | 'ENDED'
  | 'UNAUTHORIZED'
  | 'UNREACHABLE'
  | 'NETWORK_FAILED'
  | 'PEER_REJECTED'
  | 'BUSY'
  | 'TIMEOUT'
  | 'MEDIA_FAILED'
  | 'SERVER_FAILED';

// Viro ID privacy
export type AllowCallsFromViroId = 'CONNECTIONS_ONLY' | 'EXACT_ID_ALLOWED';

// Admin roles
export type AdminRole = 'USER' | 'SUPPORT' | 'ADMIN' | 'SECURITY_ADMIN';

// Security events
export type SecurityEventSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type SecurityEventType =
  | 'REFRESH_TOKEN_REUSE'
  | 'SUSPICIOUS_ENUMERATION'
  | 'RATE_LIMIT_EXCEEDED'
  | 'DEVICE_REVOKED'
  | 'SESSION_REVOKED'
  | 'INVALID_OTP_ATTEMPT'
  | 'BLOCKED_CALL_ATTEMPT';

// Transport capabilities
export type TransportCapability = 'voice' | 'video';

// API Error codes
export type ApiErrorCode =
  | 'VALIDATION_ERROR'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'RATE_LIMITED'
  | 'CALL_TARGET_UNAVAILABLE'
  | 'CALL_NOT_AUTHORIZED'
  | 'INVALID_TARGET'
  | 'TARGET_NOT_FOUND'
  | 'DUPLICATE_VIRO_ID'
  | 'DUPLICATE_PHONE'
  | 'INVALID_E164'
  | 'TOKEN_EXPIRED'
  | 'TOKEN_REUSE_DETECTED'
  | 'DEVICE_REVOKED'
  | 'ACCOUNT_SUSPENDED'
  /** The other side has no published keys yet, so this cannot be sent encrypted. */
  | 'E2EE_NOT_AVAILABLE'
  | 'INTERNAL_ERROR';

export interface ApiError {
  code: ApiErrorCode;
  message: string;
  requestId: string;
  details?: Record<string, unknown>;
}

// Public profile (limited)
export interface PublicProfile {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  viroId: string;
}

// Contact discovery — client sends E.164 over authenticated TLS; server hashes internally
export interface ContactDiscoveryRequest {
  phonesE164: string[];
  defaultRegion?: string;
}

export interface ContactDiscoveryMatch {
  phoneE164: string;
  userId: string;
  viroId: string;
  displayName: string;
  avatarUrl: string | null;
  relationshipState: ContactRelationshipState;
}

export interface ContactDiscoveryResponse {
  matches: ContactDiscoveryMatch[];
}

// Local discovery (never contains PII)
export interface LocalDiscoveryAdvertisement {
  protocol: 'viro-reach';
  version: '1';
  ephemeralId: string;
  capabilities: TransportCapability[];
}

// Call authorization
export interface CallAuthorizeRequest {
  targetUserId: string;
  preferredRoute?: CallRouteType;
}

export interface CallAuthorizeResponse {
  callId: string;
  authorized: boolean;
  expiresAt: string;
  routeType: CallRouteType;
  sessionMaterial?: Record<string, string>;
}
