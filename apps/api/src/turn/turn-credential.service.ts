import { Injectable } from '@nestjs/common';
import { createHmac, randomBytes } from 'crypto';

export interface TurnCredentials {
  urls: string[];
  username: string;
  credential: string;
  ttlSeconds: number;
}

@Injectable()
export class TurnCredentialService {
  private readonly ttlSeconds = parseInt(process.env.TURN_CREDENTIAL_TTL_SECONDS || '3600', 10);

  generateCredentials(userId: string, deviceId: string): TurnCredentials {
    const secret = process.env.TURN_SECRET || 'dev_turn_secret';
    const realm = process.env.TURN_REALM || 'viro-reach.local';
    const host = process.env.TURN_HOST || 'localhost';
    const port = process.env.TURN_PORT || '3478';

    const expiry = Math.floor(Date.now() / 1000) + this.ttlSeconds;
    const username = `${expiry}:${userId}:${deviceId}`;
    const credential = createHmac('sha1', secret)
      .update(username)
      .digest('base64');

    return {
      urls: [
        `stun:${host}:${port}`,
        `turn:${host}:${port}?transport=udp`,
        `turn:${host}:${port}?transport=tcp`,
      ],
      username,
      credential,
      ttlSeconds: this.ttlSeconds,
    };
  }
}
