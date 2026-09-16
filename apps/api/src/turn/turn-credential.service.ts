import { Injectable, Logger } from '@nestjs/common';
import { createHmac } from 'crypto';

export interface TurnCredentials {
  urls: string[];
  username: string;
  credential: string;
  ttlSeconds: number;
}

/**
 * Issues short-lived ICE server credentials for WebRTC calls.
 *
 * The returned list always contains at least one publicly reachable STUN
 * server so that peer-to-peer connectivity works on most networks even when a
 * dedicated TURN relay has not been deployed. When a real TURN host is
 * configured (`TURN_HOST` pointing at a routable address — not localhost) the
 * list additionally advertises UDP, TCP and (optionally) TLS relay URLs so
 * calls survive symmetric NAT and restrictive firewalls / poor networks.
 *
 * Credentials follow the coturn "TURN REST API" convention
 * (`use-auth-secret` / `static-auth-secret`): the username is
 * `<expiry-unix-ts>:<userId>` and the credential is
 * `base64(HMAC-SHA1(secret, username))`. The `static-auth-secret` configured
 * on coturn MUST equal `TURN_SECRET` for relaying to authenticate.
 */
@Injectable()
export class TurnCredentialService {
  private readonly logger = new Logger('TurnCredentialService');
  private readonly ttlSeconds = parseInt(
    process.env.TURN_CREDENTIAL_TTL_SECONDS || '3600',
    10,
  );

  private static readonly DEFAULT_STUN =
    'stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302';

  generateCredentials(userId: string, deviceId: string): TurnCredentials {
    const secret = process.env.TURN_SECRET || 'dev_turn_secret';

    const expiry = Math.floor(Date.now() / 1000) + this.ttlSeconds;
    // coturn parses the timestamp before the first ':' and validates the HMAC
    // over the whole username. Keep it to the conventional `ts:userId` form.
    const username = `${expiry}:${userId}`;
    const credential = createHmac('sha1', secret)
      .update(username)
      .digest('base64');

    const urls = this.buildIceUrls();

    return {
      urls,
      username,
      credential,
      ttlSeconds: this.ttlSeconds,
    };
  }

  /**
   * Builds the ordered ICE server URL list: public STUN first (cheap, works on
   * good networks with little data), then relay URLs when a real TURN host is
   * available (UDP → TCP → TLS), which are the fallbacks for hostile networks.
   */
  private buildIceUrls(): string[] {
    const urls: string[] = [];

    const stunEnv = process.env.STUN_URLS ?? TurnCredentialService.DEFAULT_STUN;
    for (const raw of stunEnv.split(',')) {
      const url = raw.trim();
      if (url) urls.push(url);
    }

    const turnHost = (process.env.TURN_HOST || '').trim();
    if (this.isRoutableHost(turnHost)) {
      const port = process.env.TURN_PORT || '3478';
      const transports = (process.env.TURN_TRANSPORTS || 'udp,tcp')
        .split(',')
        .map((t) => t.trim().toLowerCase())
        .filter((t) => t === 'udp' || t === 'tcp');

      // The TURN server doubles as a STUN server on the same host/port.
      urls.push(`stun:${turnHost}:${port}`);
      for (const transport of transports) {
        urls.push(`turn:${turnHost}:${port}?transport=${transport}`);
      }

      // TURN over TLS (typically 443) tunnels through firewalls that block
      // plain UDP/TCP — essential for "works on the worst networks".
      const tlsPort = (process.env.TURN_TLS_PORT || '').trim();
      if (tlsPort) {
        urls.push(`turns:${turnHost}:${tlsPort}?transport=tcp`);
      }
    } else if (turnHost) {
      this.logger.warn(
        `TURN_HOST="${turnHost}" is not routable; relay disabled. ` +
          'Set TURN_HOST to a public address to enable calls behind symmetric NAT.',
      );
    }

    return urls;
  }

  private isRoutableHost(host: string): boolean {
    if (!host) return false;
    const lowered = host.toLowerCase();
    return (
      lowered !== 'localhost' &&
      lowered !== '127.0.0.1' &&
      lowered !== '0.0.0.0' &&
      lowered !== '::1'
    );
  }
}
