import { Injectable, Logger } from '@nestjs/common';
import { AccessToken, TrackSource, VideoGrant } from 'livekit-server-sdk';

export interface LiveKitCredentials {
  url: string;
  token: string;
  roomName: string;
}

/**
 * Mints short-lived, room-scoped LiveKit access tokens server-side.
 * LIVEKIT_API_SECRET never leaves this process — only the signed JWT does.
 */
@Injectable()
export class LiveKitService {
  private readonly logger = new Logger('LiveKitService');
  private readonly apiKey = process.env.LIVEKIT_API_KEY || '';
  private readonly apiSecret = process.env.LIVEKIT_API_SECRET || '';
  private readonly url = process.env.LIVEKIT_URL || '';
  private readonly ttlSeconds = parseInt(
    process.env.LIVEKIT_TOKEN_TTL_SECONDS || '300',
    10,
  );

  /** Room name derived from the Viro callSessionId — the authoritative link. */
  roomNameFor(callId: string): string {
    return `viro-call-${callId}`;
  }

  /** Room name derived from a conference roomId — kept distinct from 1:1 call rooms. */
  roomNameForConference(conferenceId: string): string {
    return `viro-conf-${conferenceId}`;
  }

  isConfigured(): boolean {
    return !!(this.apiKey && this.apiSecret && this.url);
  }

  async generateToken(
    callId: string,
    userId: string,
  ): Promise<LiveKitCredentials> {
    return this.generateTokenForRoom(this.roomNameFor(callId), userId);
  }

  /** Same audio-only grant, for a caller-supplied room name (e.g. a conference room). */
  async generateTokenForRoom(
    roomName: string,
    userId: string,
  ): Promise<LiveKitCredentials> {
    if (!this.isConfigured()) {
      throw new Error('LiveKit is not configured (LIVEKIT_API_KEY/SECRET/URL).');
    }
    const at = new AccessToken(this.apiKey, this.apiSecret, {
      identity: userId,
      ttl: this.ttlSeconds,
    });
    const grant: VideoGrant = {
      roomJoin: true,
      room: roomName,
      canPublish: true,
      // Voice only for now — video is a separate, later feature.
      canPublishSources: [TrackSource.MICROPHONE],
      canSubscribe: true,
      canPublishData: false,
    };
    at.addGrant(grant);
    const token = await at.toJwt();
    this.logger.log(`LIVEKIT_TOKEN_ISSUED room=${roomName} identity=${userId}`);
    return { url: this.url, token, roomName };
  }
}
