import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { createHmac, randomBytes } from 'crypto';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { BlocksService } from '../blocks/blocks.service';

export interface OfflineTrustMaterial {
  peerUserId: string;
  trustToken: string;
  epoch: number;
  expiresAt: string;
}

export interface OfflineCallTicket {
  ticket: string;
  peerUserId: string;
  expiresAt: string;
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
    const secret = process.env.EPHEMERAL_SIGNING_SECRET || process.env.JWT_ACCESS_SECRET || 'dev_ephemeral';
    return secret;
  }

  private deriveTrustToken(userId: string, peerUserId: string, epoch: number): string {
    const [a, b] = [userId, peerUserId].sort();
    const mac = createHmac('sha256', this.signingSecret())
      .update(`trust:${a}:${b}:${epoch}`)
      .digest('base64url');
    return mac;
  }

  private deriveBindingTag(trustToken: string, ephemeralId: string): string {
    return createHmac('sha256', trustToken)
      .update(ephemeralId)
      .digest('base64url')
      .slice(0, 22);
  }

  async getTrustMaterial(userId: string): Promise<OfflineTrustMaterial[]> {
    const peerIds = await this.getAuthorizedPeerIds(userId);
    const expiresAt = new Date(Date.now() + OFFLINE_TRUST_TTL_SECONDS * 1000).toISOString();
    const epoch = Math.floor(Date.now() / (OFFLINE_TRUST_TTL_SECONDS * 1000));

    return peerIds.map((peerUserId) => ({
      peerUserId,
      trustToken: this.deriveTrustToken(userId, peerUserId, epoch),
      epoch,
      expiresAt,
    }));
  }

  async issueOfflineCallTickets(userId: string): Promise<OfflineCallTicket[]> {
    const peers = await this.getAuthorizedPeerIds(userId);
    const expiresAt = new Date(Date.now() + OFFLINE_CALL_TICKET_TTL_SECONDS * 1000);
    const exp = Math.floor(expiresAt.getTime() / 1000);

    return peers.map((peerUserId) => {
      const nonce = randomBytes(8).toString('hex');
      const payload = `${userId}:${peerUserId}:${exp}:${nonce}`;
      const sig = createHmac('sha256', this.signingSecret()).update(`call:${payload}`).digest('base64url');
      const ticket = Buffer.from(`${payload}:${sig}`).toString('base64url');
      return { ticket, peerUserId, expiresAt: expiresAt.toISOString() };
    });
  }

  verifyOfflineCallTicket(ticket: string, callerUserId: string, calleeUserId: string): boolean {
    try {
      const decoded = Buffer.from(ticket, 'base64url').toString('utf8');
      const parts = decoded.split(':');
      if (parts.length !== 5) return false;
      const [uid, peer, expStr, nonce, sig] = parts;
      const exp = parseInt(expStr, 10);
      if (Date.now() / 1000 > exp) return false;
      if (uid !== callerUserId || peer !== calleeUserId) return false;
      const expected = createHmac('sha256', this.signingSecret())
        .update(`call:${uid}:${peer}:${expStr}:${nonce}`)
        .digest('base64url');
      return sig === expected;
    } catch {
      return false;
    }
  }

  computeBindingTagForTest(trustToken: string, ephemeralId: string): string {
    return this.deriveBindingTag(trustToken, ephemeralId);
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
