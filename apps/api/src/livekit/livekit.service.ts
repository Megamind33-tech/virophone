import { Injectable, Logger } from '@nestjs/common';
import { AccessToken, RoomServiceClient, TrackSource, VideoGrant } from 'livekit-server-sdk';

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

  /** A Moment's live room — distinct from calls and conferences. */
  roomNameForMoment(momentId: string): string {
    return `viro-moment-${momentId}`;
  }

  /**
   * Camera and microphone for the people in one Moment, and nothing else.
   *
   * Video is allowed here and only here: calls and conferences keep their
   * voice-only grant. Holding this token publishes nothing by itself — the
   * phone turns the camera or microphone on only when its person chooses to.
   * Screens are not publishable, and data stays on Viro's own socket.
   */
  async generateMomentToken(momentId: string, userId: string, displayName: string): Promise<LiveKitCredentials> {
    if (!this.isConfigured()) {
      throw new Error('LiveKit is not configured (LIVEKIT_API_KEY/SECRET/URL).');
    }
    const roomName = this.roomNameForMoment(momentId);
    const at = new AccessToken(this.apiKey, this.apiSecret, {
      identity: userId,
      name: displayName,
      ttl: this.ttlSeconds,
    });
    at.addGrant({
      roomJoin: true,
      room: roomName,
      canPublish: true,
      canPublishSources: [TrackSource.CAMERA, TrackSource.MICROPHONE],
      canSubscribe: true,
      canPublishData: false,
    });
    const token = await at.toJwt();
    this.logger.log(`LIVEKIT_MOMENT_TOKEN_ISSUED room=${roomName} identity=${userId}`);
    return { url: this.url, token, roomName };
  }

  /**
   * A token only admits; it does not keep anyone out afterwards. When someone
   * leaves a Moment, is separated by a block, or the Moment ends, the media
   * server is told directly. Best effort: a failure here is logged, and the
   * phone also disconnects itself on the same event.
   */
  async removeFromRoom(roomName: string, userId: string): Promise<void> {
    try {
      await this.roomService()?.removeParticipant(roomName, userId);
    } catch (e) {
      // Not in the media room at all is the common, harmless case.
      this.logger.debug(`LIVEKIT_REMOVE_SKIPPED room=${roomName} identity=${userId} ${(e as Error).message}`);
    }
  }

  async closeRoom(roomName: string): Promise<void> {
    try {
      await this.roomService()?.deleteRoom(roomName);
    } catch (e) {
      this.logger.debug(`LIVEKIT_CLOSE_SKIPPED room=${roomName} ${(e as Error).message}`);
    }
  }

  private rooms?: RoomServiceClient;
  private roomService(): RoomServiceClient | undefined {
    if (!this.isConfigured()) return undefined;
    // The server API speaks HTTP on the same host as the client websocket,
    // unless the deployment names an internal address for it.
    const host = process.env.LIVEKIT_API_URL || this.url.replace(/^ws(s?):\/\//,'http$1://');
    this.rooms ??= new RoomServiceClient(host, this.apiKey, this.apiSecret);
    return this.rooms;
  }
}
