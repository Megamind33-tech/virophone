import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Block } from '../database/entities/block.entity';
import { Profile } from '../database/entities/profile.entity';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';
import { PushService } from '../push/push.service';
import { publicAvatarUrl } from '../users/avatar.util';
import { VisibilityService } from '../users/visibility.service';
import { RealtimeRegistry } from '../realtime/realtime.registry';


/**
 * Connections are how people reach each other without a phone number: find
 * someone by Viro ID or email, request, and once accepted both can call and
 * message (calls.service allows ACCEPTED connections).
 *
 * A declined request is never revealed: the requester keeps seeing
 * "Requested", and a repeat request from them stays quietly declined.
 */
@Injectable()
export class ConnectionsService {
  private readonly logger = new Logger(ConnectionsService.name);

  constructor(
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    private readonly pushService: PushService,
    private readonly visibility: VisibilityService,
    private readonly realtime: RealtimeRegistry,
  ) {}

  async list(userId: string) {
    const rows = await this.connectionRepo.find({
      where: [{ requesterUserId: userId }, { recipientUserId: userId }],
      order: { createdAt: 'DESC' },
    });
    // Hide declined requests from the person who declined, show them as pending to the requester;
    // removed connections are gone for both.
    const visible = rows.filter((c) =>
      c.status === 'ACCEPTED' || c.status === 'PENDING' || (c.status === 'REJECTED' && c.requesterUserId === userId));
    const peerIds = Array.from(new Set(visible.map((c) => (c.requesterUserId === userId ? c.recipientUserId : c.requesterUserId))));
    const blocks = peerIds.length
      ? await this.blockRepo.find({
          where: [
            { blockerUserId: userId, blockedUserId: In(peerIds) },
            { blockerUserId: In(peerIds), blockedUserId: userId },
          ],
        })
      : [];
    const blocked = new Set(blocks.map((b) => (b.blockerUserId === userId ? b.blockedUserId : b.blockerUserId)));
    const profiles = peerIds.length ? await this.profileRepo.find({ where: { userId: In(peerIds) } }) : [];
    const byId = new Map(profiles.map((p) => [p.userId, p]));
    const photoOk = await this.visibility.filterVisible(
      userId,
      profiles.map((p) => ({ userId: p.userId, setting: p.photoVisibility })),
    );
    return visible
      .filter((c) => !blocked.has(c.requesterUserId === userId ? c.recipientUserId : c.requesterUserId))
      .map((c) => {
        const peerId = c.requesterUserId === userId ? c.recipientUserId : c.requesterUserId;
        return this.toDto(c, userId, byId.get(peerId), photoOk.has(peerId));
      });
  }

  async create(requesterId: string, targetUserId: string) {
    if (requesterId === targetUserId) {
      throw new ViroException('VALIDATION_ERROR', 'Cannot connect to yourself.', HttpStatus.BAD_REQUEST);
    }

    const blocked = await this.blockRepo.findOne({
      where: [
        { blockerUserId: targetUserId, blockedUserId: requesterId },
        { blockerUserId: requesterId, blockedUserId: targetUserId },
      ],
    });
    if (blocked) {
      throw new ViroException('CALL_TARGET_UNAVAILABLE', 'This person is currently unavailable.', HttpStatus.NOT_FOUND);
    }
    const target = await this.profileRepo.findOne({ where: { userId: targetUserId } });
    if (!target) {
      throw new ViroException('NOT_FOUND', 'This person is not on Viro.', HttpStatus.NOT_FOUND);
    }

    // They already asked me: asking back is a yes.
    const reverse = await this.connectionRepo.findOne({
      where: { requesterUserId: targetUserId, recipientUserId: requesterId },
    });
    if (reverse?.status === 'ACCEPTED') return this.toDto(reverse, requesterId, target);
    if (reverse?.status === 'PENDING') return this.accept(reverse.id, requesterId);

    let mine = await this.connectionRepo.findOne({
      where: { requesterUserId: requesterId, recipientUserId: targetUserId },
    });
    if (mine && (mine.status === 'PENDING' || mine.status === 'ACCEPTED' || mine.status === 'REJECTED')) {
      return this.toDto(mine, requesterId, target);
    }
    if (mine) {
      // Removed earlier (REVOKED): a fresh request.
      mine.status = 'PENDING';
      mine.acceptedAt = null;
      mine.createdAt = new Date();
    } else {
      mine = this.connectionRepo.create({ requesterUserId: requesterId, recipientUserId: targetUserId, status: 'PENDING' });
    }
    const saved = await this.connectionRepo.save(mine);

    const me = await this.profileRepo.findOne({ where: { userId: requesterId } });
    await this.notify(targetUserId, {
      title: 'Connection request',
      body: `${this.label(me)} wants to connect with you on Viro.`,
      data: { type: 'connection_request', connectionId: saved.id, fromUserId: requesterId },
    });
    return this.toDto(saved, requesterId, target);
  }

  async accept(connectionId: string, userId: string) {
    const connection = await this.connectionRepo.findOne({ where: { id: connectionId } });
    if (!connection || connection.recipientUserId !== userId || connection.status === 'REVOKED') {
      throw new ViroException('NOT_FOUND', 'Connection not found.', HttpStatus.NOT_FOUND);
    }
    const wasAccepted = connection.status === 'ACCEPTED';
    connection.status = 'ACCEPTED';
    connection.acceptedAt = connection.acceptedAt ?? new Date();
    const saved = await this.connectionRepo.save(connection);
    await this.refreshMoments(connection.requesterUserId, connection.recipientUserId);
    const [me, requester] = await Promise.all([
      this.profileRepo.findOne({ where: { userId } }),
      this.profileRepo.findOne({ where: { userId: connection.requesterUserId } }),
    ]);
    if (!wasAccepted) {
      await this.notify(connection.requesterUserId, {
        title: 'Connection accepted',
        body: `${this.label(me)} accepted your request. You can now call and message each other.`,
        data: { type: 'connection_accepted', connectionId: saved.id, fromUserId: userId },
      });
    }
    return this.toDto(saved, userId, requester ?? undefined);
  }

  async reject(connectionId: string, userId: string) {
    const connection = await this.connectionRepo.findOne({ where: { id: connectionId } });
    if (!connection || connection.recipientUserId !== userId) {
      throw new ViroException('NOT_FOUND', 'Connection not found.', HttpStatus.NOT_FOUND);
    }
    connection.status = 'REJECTED';
    return this.toDto(await this.connectionRepo.save(connection), userId);
  }

  /** Removes a connection or cancels a request — either side may. */
  async revoke(connectionId: string, userId: string) {
    const connection = await this.connectionRepo.findOne({ where: { id: connectionId } });
    if (!connection) {
      throw new ViroException('NOT_FOUND', 'Connection not found.', HttpStatus.NOT_FOUND);
    }
    if (connection.requesterUserId !== userId && connection.recipientUserId !== userId) {
      throw new ViroException('FORBIDDEN', 'Not authorized.', HttpStatus.FORBIDDEN);
    }
    connection.status = 'REVOKED';
    const saved = await this.connectionRepo.save(connection);
    await this.refreshMoments(connection.requesterUserId, connection.recipientUserId);
    return this.toDto(saved, userId);
  }

  private async refreshMoments(...userIds: string[]) {
    await Promise.allSettled(userIds.map(id => this.realtime.deliverToUser(id, { type: 'moment.updated', payload: {} })));
  }

  private async notify(userId: string, payload: { title: string; body: string; data: Record<string, string> }) {
    try {
      await this.pushService.sendToUser(userId, payload);
    } catch (e) {
      this.logger.warn(`CONNECTION_PUSH_FAILED ${(e as Error).message}`);
    }
  }

  private label(p: Profile | null | undefined): string {
    const name = p?.displayName?.trim();
    if (name && p?.viroId) return `${name} (${p.viroId})`;
    return name || p?.viroId || 'Someone';
  }

  private toDto(
    c: { id: string; requesterUserId: string; recipientUserId: string; status: string; createdAt?: Date; acceptedAt?: Date | null },
    viewerUserId: string,
    peer?: Profile,
    showPhoto = true,
  ) {
    const outgoing = c.requesterUserId === viewerUserId;
    return {
      id: c.id,
      requesterUserId: c.requesterUserId,
      recipientUserId: c.recipientUserId,
      // A requester is never told they were declined.
      status: c.status === 'REJECTED' && outgoing ? 'PENDING' : c.status,
      direction: outgoing ? 'OUTGOING' : 'INCOMING',
      peerUserId: outgoing ? c.recipientUserId : c.requesterUserId,
      peerDisplayName: peer?.displayName || null,
      peerAvatarUrl: peer && showPhoto ? publicAvatarUrl(peer.avatarUrl) : null,
      peerViroId: peer?.viroId ?? null,
      createdAt: c.createdAt ?? null,
      acceptedAt: c.acceptedAt ?? null,
    };
  }
}
