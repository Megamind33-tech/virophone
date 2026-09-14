import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { createHmac, randomBytes } from 'crypto';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { BlocksService } from '../blocks/blocks.service';

export const OFFLINE_TRUST_PROTOCOL_VERSION = 1;

export interface OfflineTrustMaterial {
  peerUserId: string;
  trustToken: string;
  epoch: number;
  expiresAt: string;
  protocolVersion: number;
  deviceId: string;
}

export interface OfflineCallTicket {
  ticket: string;
  peerUserId: string;
  expiresAt: string;
  protocolVersion: number;
}

const OFFLINE_TRUST_TTL_SECONDS = parseInt(process.env.OFFLINE_TRUST_TTL_SECONDS || '86400', 10);
const OFFLINE_CALL_TICKET_TTL_SECONDS = parseInt(process.env.OFFLINE_CALL_TICKET_TTL_SECONDS || '3600', 10);

@Injectable()
export class OfflineTrustService {
  constructor(
    @InjectRepository(ContactMatch) private readonly matchRepo: Repository<ContactMatch>,
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    private readonly blocksService: BlocksService,
  ) {}

  private signingSecret(): string {
    const secret = process.env.EPHEMERAL_SIGNING_SECRET;
    if (!secret) {
      throw new Error('EPHEMERAL_SIGNING_SECRET is required for offline trust');
    }
    return secret;
  }

  /** Server-only minting. Binds user pair + issuing device + epoch. */
  private deriveTrustToken(userId: string, deviceId: string, peerUserId: string, epoch: number): string {
    const [a, b] = [userId, peerUserId].sort();
    return createHmac('sha256', this.signingSecret())
      .update(`trust:v${OFFLINE_TRUST_PROTOCOL_VERSION}:${a}:${b}:${deviceId}:${epoch}`)
      .digest('base64url');
  }

  computeBindingTag(trustToken: string, ephemeralId: string): string {
    return createHmac('sha256', trustToken)
      .update(ephemeralId)
      .digest('base64url')
      .slice(0, 22);
  }

  async getTrustMaterial(userId: string, deviceId: string): Promise<OfflineTrustMaterial[]> {
    const peerIds = await this.getAuthorizedPeerIds(userId);
    const expiresAt = new Date(Date.now() + OFFLINE_TRUST_TTL_SECONDS * 1000).toISOString();
    const epoch = Math.floor(Date.now() / (OFFLINE_TRUST_TTL_SECONDS * 1000));

    return peerIds.map((peerUserId) => ({
      peerUserId,
      trustToken: this.deriveTrustToken(userId, deviceId, peerUserId, epoch),
      epoch,
      expiresAt,
      protocolVersion: OFFLINE_TRUST_PROTOCOL_VERSION,
      deviceId,
    }));
  }

  async issueOfflineCallTickets(userId: string, deviceId: string): Promise<OfflineCallTicket[]> {
    const peers = await this.getAuthorizedPeerIds(userId);
    const expiresAt = new Date(Date.now() + OFFLINE_CALL_TICKET_TTL_SECONDS * 1000);
    const exp = Math.floor(expiresAt.getTime() / 1000);

    return peers.map((peerUserId) => {
      const nonce = randomBytes(16).toString('hex');
      const payload = `v${OFFLINE_TRUST_PROTOCOL_VERSION}:${userId}:${deviceId}:${peerUserId}:${exp}:${nonce}`;
      const sig = createHmac('sha256', this.signingSecret()).update(`call:${payload}`).digest('base64url');
      const ticket = Buffer.from(`${payload}:${sig}`).toString('base64url');
      return { ticket, peerUserId, expiresAt: expiresAt.toISOString(), protocolVersion: OFFLINE_TRUST_PROTOCOL_VERSION };
    });
  }

  verifyOfflineCallTicket(
    ticket: string,
    callerUserId: string,
    callerDeviceId: string,
    calleeUserId: string,
  ): boolean {
    try {
      const decoded = Buffer.from(ticket, 'base64url').toString('utf8');
      const parts = decoded.split(':');
      if (parts.length !== 7) return false;
      const [ver, uid, did, peer, expStr, nonce, sig] = parts;
      if (ver !== `v${OFFLINE_TRUST_PROTOCOL_VERSION}`) return false;
      const exp = parseInt(expStr, 10);
      if (Date.now() / 1000 > exp) return false;
      if (uid !== callerUserId || did !== callerDeviceId || peer !== calleeUserId) return false;
      const payload = `${ver}:${uid}:${did}:${peer}:${expStr}:${nonce}`;
      const expected = createHmac('sha256', this.signingSecret()).update(`call:${payload}`).digest('base64url');
      return sig === expected;
    } catch {
      return false;
    }
  }

  private async getAuthorizedPeerIds(userId: string): Promise<string[]> {
    const peers = new Set<string>();

    const matches = await this.matchRepo.find({ where: { userId } });
    for (const m of matches) {
      if (!(await this.blocksService.isBlocked(userId, m.matchedUserId))) {
        peers.add(m.matchedUserId);
      }
    }

    const connections = await this.connectionRepo.find({
      where: [
        { requesterUserId: userId, status: 'ACCEPTED' },
        { recipientUserId: userId, status: 'ACCEPTED' },
      ],
    });
    for (const c of connections) {
      const peer = c.requesterUserId === userId ? c.recipientUserId : c.requesterUserId;
      if (!(await this.blocksService.isBlocked(userId, peer))) {
        peers.add(peer);
      }
    }

    return Array.from(peers);
  }
}
