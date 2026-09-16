import type {
  AllowCallsFromViroId,
  ApiError,
  CallAuthorizeRequest,
  CallAuthorizeResponse,
  ContactDiscoveryRequest,
  ContactDiscoveryResponse,
  PublicProfile,
} from '@viro-reach/shared-types';

export const API_VERSION = 'v1';
export const API_BASE = `/api/${API_VERSION}`;

// Auth
export interface OtpRequestBody {
  phoneE164: string;
}

export interface OtpRequestResponse {
  challengeId: string;
  expiresAt: string;
}

export interface OtpVerifyBody {
  challengeId: string;
  code: string;
  devicePublicKey: string;
  platform: 'ANDROID' | 'IOS' | 'WEB';
  appVersion: string;
}

export interface OtpVerifyResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  userId: string;
  deviceId: string;
  isNewUser: boolean;
}

export interface RefreshTokenBody {
  refreshToken: string;
}

export interface RefreshTokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

// Device
export interface DeviceRegisterBody {
  publicKey: string;
  platform: 'ANDROID' | 'IOS' | 'WEB';
  appVersion: string;
}

export interface DeviceResponse {
  id: string;
  platform: string;
  appVersion: string;
  createdAt: string;
  lastSeenAt: string;
}

// Me
export interface MeResponse {
  userId: string;
  phoneE164: string;
  displayName: string;
  avatarUrl: string | null;
  viroId: string | null;
  allowCallsFromViroId: AllowCallsFromViroId;
}

export interface MeUpdateBody {
  displayName?: string;
  avatarUrl?: string | null;
  viroId?: string;
  allowCallsFromViroId?: AllowCallsFromViroId;
}

// Connections
export interface CreateConnectionBody {
  targetUserId: string;
}

export interface ConnectionResponse {
  id: string;
  requesterUserId: string;
  recipientUserId: string;
  status: string;
  createdAt: string;
  acceptedAt: string | null;
}

// Blocks
export interface CreateBlockBody {
  blockedUserId: string;
}

// Re-export domain types used in contracts
export type {
  ApiError,
  CallAuthorizeRequest,
  CallAuthorizeResponse,
  ContactDiscoveryRequest,
  ContactDiscoveryResponse,
  PublicProfile,
};

// Endpoint registry
export const ENDPOINTS = {
  auth: {
    otpRequest: `${API_BASE}/auth/otp/request`,
    otpVerify: `${API_BASE}/auth/otp/verify`,
    refresh: `${API_BASE}/auth/refresh`,
    logout: `${API_BASE}/auth/logout`,
  },
  devices: {
    register: `${API_BASE}/devices/register`,
    list: `${API_BASE}/devices`,
    delete: (id: string) => `${API_BASE}/devices/${id}`,
  },
  me: {
    get: `${API_BASE}/me`,
    update: `${API_BASE}/me`,
    export: `${API_BASE}/me/export`,
    subscription: `${API_BASE}/me/subscription`,
  },
  plans: {
    list: `${API_BASE}/plans`,
  },
  contacts: {
    discover: `${API_BASE}/contacts/discover`,
  },
  directory: {
    exact: (viroId: string) => `${API_BASE}/directory/exact/${encodeURIComponent(viroId)}`,
  },
  connections: {
    list: `${API_BASE}/connections`,
    create: `${API_BASE}/connections`,
    accept: (id: string) => `${API_BASE}/connections/${id}/accept`,
    reject: (id: string) => `${API_BASE}/connections/${id}/reject`,
    delete: (id: string) => `${API_BASE}/connections/${id}`,
  },
  devices: {
    register: `${API_BASE}/devices/register`,
    list: `${API_BASE}/devices`,
    delete: (id: string) => `${API_BASE}/devices/${id}`,
  },
  blocks: {
    create: `${API_BASE}/blocks`,
    list: `${API_BASE}/blocks`,
    delete: (userId: string) => `${API_BASE}/blocks/${userId}`,
  },
  calls: {
    authorize: `${API_BASE}/calls/authorize`,
    history: `${API_BASE}/calls/history`,
    events: (id: string) => `${API_BASE}/calls/${id}/events`,
    end: (id: string) => `${API_BASE}/calls/${id}/end`,
  },
  health: {
    live: '/health/live',
    ready: '/health/ready',
    metrics: '/health/metrics',
  },
} as const;
