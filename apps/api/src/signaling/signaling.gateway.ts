import {
  WebSocketGateway,
  WebSocketServer,
  OnGatewayConnection,
  OnGatewayDisconnect,
  OnGatewayInit,
  SubscribeMessage,
  MessageBody,
  ConnectedSocket,
} from '@nestjs/websockets';
import { Logger, OnModuleInit } from '@nestjs/common';
import { RealtimeRegistry } from '../realtime/realtime.registry';
import { SignalingDeliveryService } from './signaling-delivery.service';
import { Server } from 'ws';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Device } from '../database/entities/device.entity';
import { User } from '../database/entities/user.entity';
import { RedisService } from '../redis/redis.service';
import { PresenceService } from '../presence/presence.service';
import { CallSessionService } from '../calls/call-session.service';
import { CallsService } from '../calls/calls.service';
import { ConferenceService } from '../conference/conference.service';
import { MetricsService } from '../metrics/metrics.module';
import { MessagesService } from '../messages/messages.service';

interface AuthenticatedSocket extends WebSocket {
  userId?: string;
  deviceId?: string;
}

interface JwtPayload {
  sub: string;
  deviceId: string;
}

export type SignalingEventType =
  | 'call.incoming'
  | 'call.invite'
  | 'call.incoming'
  | 'call.offer'
  | 'call.answer'
  | 'call.ice'
  | 'call.iceRestart'
  | 'call.ringing'
  | 'call.accept'
  | 'call.reject'
  | 'call.end'
  | 'call.busy'
  | 'call.hold'
  | 'call.unhold'
  | 'call.error';

export interface SignalingEnvelope {
  type: SignalingEventType;
  callId: string;
  targetDeviceId?: string;
  payload?: unknown;
}

/** TTL for the ws:* presence keys, re-armed on every inbound frame. */
const PRESENCE_TTL_SECONDS = 3600;

@WebSocketGateway({ path: '/api/v1/signaling/ws' })
export class SignalingGateway
  implements OnGatewayConnection, OnGatewayDisconnect, OnModuleInit
{
  @WebSocketServer()
  server!: Server;

  private readonly logger = new Logger('Signaling');

  constructor(
    private readonly jwtService: JwtService,
    private readonly redis: RedisService,
    private readonly presenceService: PresenceService,
    private readonly callSessionService: CallSessionService,
    private readonly callsService: CallsService,
    private readonly conferenceService: ConferenceService,
    private readonly realtimeRegistry: RealtimeRegistry,
    private readonly metrics: MetricsService,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    @InjectRepository(User) private readonly userRepo: Repository<User>,
    private readonly signalingDelivery: SignalingDeliveryService,
    private readonly messagesService: MessagesService,
  ) {}

  onModuleInit(): void {
    this.realtimeRegistry.registerSink({
      deliverToDevice: (deviceId, message) =>
        this.deliverToDevice(deviceId, message),
    });
    this.signalingDelivery.registerDeliverer((deviceId, message) =>
      this.deliverToDevice(deviceId, { ...message }) > 0,
    );
  }

  async handleConnection(client: AuthenticatedSocket, ...args: unknown[]) {
    try {
      const request = args[0] as { url?: string };
      const url = new URL(request.url || '', 'http://localhost');
      const token = url.searchParams.get('token');
      if (!token) {
        client.close(4001, 'Unauthorized');
        return;
      }

      const payload = this.jwtService.verify<JwtPayload>(token, {
        secret: process.env.JWT_ACCESS_SECRET || 'dev_access_secret',
      });

      const user = await this.userRepo.findOne({ where: { id: payload.sub } });
      if (!user || user.status !== 'ACTIVE') {
        client.close(4003, 'Suspended');
        return;
      }

      const device = await this.deviceRepo.findOne({ where: { id: payload.deviceId } });
      if (!device || device.revokedAt) {
        client.close(4003, 'Device revoked');
        return;
      }

      client.userId = payload.sub;
      client.deviceId = payload.deviceId;
      this.metrics.wsConnections += 1;

      await this.redis.setJson(
        `ws:device:${payload.deviceId}`,
        { userId: payload.sub, connectedAt: Date.now() },
        3600,
      );
      await this.redis.setJson(
        `ws:user:${payload.sub}`,
        { deviceId: payload.deviceId, connectedAt: Date.now() },
        3600,
      );
      // Track every connected device so realtime delivery can fan out to all
      // of a user's devices (multi-device).
      await this.redis.sAdd(`ws:userdevices:${payload.sub}`, payload.deviceId, 3600);
      await this.presenceService.setPresence(payload.sub, 'ONLINE');
    } catch {
      client.close(4001, 'Unauthorized');
    }
  }

  async handleDisconnect(client: AuthenticatedSocket) {
    if (client.deviceId) {
      await this.redis.del(`ws:device:${client.deviceId}`);
      if (client.userId) {
        await this.redis.sRem(`ws:userdevices:${client.userId}`, client.deviceId);
      }
    }
    if (client.userId) {
      await this.redis.del(`ws:user:${client.userId}`);
      await this.presenceService.setPresence(client.userId, 'OFFLINE');
    }
  }

  @SubscribeMessage('signaling')
  async handleSignaling(
    @ConnectedSocket() client: AuthenticatedSocket,
    @MessageBody() envelope: SignalingEnvelope,
  ) {
    if (!client.userId || !client.deviceId) {
      return { error: 'unauthorized' };
    }
    this.metrics.wsMessages += 1;

    // Traffic from a device proves its socket is alive. Without this the
    // presence keys — written once on connect with a 1h TTL — silently
    // expire under any call or session that outlives the TTL, and the
    // device stops being deliverable while still able to send.
    await this.touchPresence(client.userId, client.deviceId);

    const { type, callId, targetDeviceId, payload } = envelope;
    if (!callId || !type) {
      return { error: 'invalid_envelope' };
    }
    this.logger.log(
      `RECV type=${type} callId=${callId} from=${client.deviceId} target=${targetDeviceId ?? '(none)'}`,
    );

    const session = await this.callSessionService.getSession(callId);
    if (!session) {
      return { error: 'call_not_found' };
    }

    const isParticipant = await this.callSessionService.isParticipant(
      callId,
      client.userId,
      client.deviceId,
    );
    if (!isParticipant) {
      return { error: 'not_participant' };
    }

    const calleeDevices = this.callSessionService.calleeDevicesOf(session);

    const allowedRecipient = (deviceId: string) =>
      deviceId === session.callerDeviceId ||
      calleeDevices.includes(deviceId) ||
      (session.invitedDeviceIds ?? []).includes(deviceId);

    if (targetDeviceId && !allowedRecipient(targetDeviceId)) {
      return { error: 'invalid_target' };
    }

    if (type === 'call.invite' && client.deviceId !== session.callerDeviceId) {
      return { error: 'only_caller_may_invite' };
    }
    if (type === 'call.answer' && !calleeDevices.includes(client.deviceId)) {
      return { error: 'only_callee_may_answer' };
    }

    const stateTransitions: Partial<Record<SignalingEventType, string>> = {
      'call.ringing': 'RINGING',
      'call.accept': 'CONNECTING',
      // The answer carries the callee's SDP: media negotiation is under way, so
      // the session is considered connected from the server's point of view.
      'call.answer': 'ACTIVE',
      'call.end': 'ENDED',
      'call.reject': 'ENDED',
      'call.busy': 'ENDED',
    };
    const nextState = stateTransitions[type];
    if (nextState) {
      await this.callSessionService.updateState(callId, nextState as 'RINGING');
    }

    // Mirror key lifecycle transitions into the durable calls table.
    if (type === 'call.ringing') {
      await this.callsService.markRinging(callId).catch(() => undefined);
    } else if (type === 'call.answer') {
      await this.callsService.markActive(callId).catch(() => undefined);
    }

    // First callee device to accept/answer wins; others are told the call is busy.
    if (type === 'call.accept' || type === 'call.answer') {
      const others = await this.callSessionService.markAnswered(callId, client.deviceId);
      for (const other of others) {
        await this.realtimeRegistry.deliverToDevice(other, {
          type: 'call.busy',
          callId,
          fromUserId: client.userId,
          fromDeviceId: client.deviceId,
        });
      }
    }

    const recipients = this.ringAllRecipients(session, client.deviceId, type, targetDeviceId);
    if (recipients.length === 0) {
      this.logger.warn(`NO_RECIPIENTS type=${type} callId=${callId} from=${client.deviceId}`);
    }
    let delivered = false;
    for (const recipientDeviceId of recipients) {
      const ok = await this.realtimeRegistry.deliverToDevice(recipientDeviceId, {
        type,
        callId,
        fromUserId: client.userId,
        fromDeviceId: client.deviceId,
        payload,
      });
      this.logger.log(
        `SEND type=${type} callId=${callId} to=${recipientDeviceId} delivered=${ok}`,
      );
      if (ok) delivered = true;
    }

    if (!delivered) {
      // The peer's socket is gone (app closed / lost connection). Tell the
      // sender explicitly instead of leaving the call hanging in "connecting".
      this.logger.warn(`UNDELIVERED type=${type} callId=${callId} from=${client.deviceId}`);
      return { delivered: false, reason: 'peer_unreachable' };
    }

    return { delivered: true };
  }

  /**
   * Ring-all: until a callee device answers, caller invite/offer/ICE fans out
   * to every callee device. After answer, media goes only to that device.
   */
  private ringAllRecipients(
    session: {
      callerDeviceId: string;
      calleeDeviceId: string;
      calleeDeviceIds?: string[];
      invitedDeviceIds?: string[];
      answeredDeviceId?: string;
    },
    senderDeviceId: string,
    type: string,
    targetDeviceId?: string,
  ): string[] {
    const calleeDevices = this.callSessionService.calleeDevicesOf(session as any);
    const invited = session.invitedDeviceIds ?? [];
    if (session.answeredDeviceId) {
      if (targetDeviceId) return [targetDeviceId];
      // Once a third party has been added the call is no longer two-sided, so
      // an untargeted frame has to reach every other participant rather than
      // the single "other end" — otherwise hanging up or muting is invisible
      // to everyone except one of them.
      if (invited.length > 0) {
        const everyone = new Set([
          session.callerDeviceId,
          session.answeredDeviceId,
          ...invited,
        ]);
        everyone.delete(senderDeviceId);
        return Array.from(everyone);
      }
      return senderDeviceId === session.callerDeviceId
        ? [session.answeredDeviceId]
        : [session.callerDeviceId];
    }
    const ringingFanout = type === 'call.invite' || type === 'call.offer' || type === 'call.ice';
    if (senderDeviceId === session.callerDeviceId && ringingFanout) {
      return calleeDevices;
    }
    if (targetDeviceId) return [targetDeviceId];
    return senderDeviceId === session.callerDeviceId
      ? [session.calleeDeviceId]
      : [session.callerDeviceId];
  }

  /**
   * Mesh conference signaling. Members relay offer/answer/ICE peer-to-peer;
   * the server tracks membership and fans join/leave to the room.
   */
  /**
   * Chat presence that is not worth persisting: "typing…" and "recording
   * voice…". Relayed only to the other participants of a conversation the
   * sender belongs to.
   */
  @SubscribeMessage('chat')
  async handleChat(
    @ConnectedSocket() client: AuthenticatedSocket,
    @MessageBody() envelope: { type?: string; conversationId?: string; state?: string },
  ) {
    if (!client.userId || !client.deviceId) return { error: 'unauthorized' };
    await this.touchPresence(client.userId, client.deviceId);
    if (envelope?.type !== 'chat.typing' || !envelope.conversationId || !envelope.state) {
      return { error: 'invalid_envelope' };
    }
    return this.messagesService.relayTyping(client.userId, envelope.conversationId, envelope.state);
  }

  @SubscribeMessage('conference')
  async handleConference(
    @ConnectedSocket() client: AuthenticatedSocket,
    @MessageBody() envelope: SignalingEnvelope & { roomId?: string },
  ) {
    if (!client.userId || !client.deviceId) return { error: 'unauthorized' };
    this.metrics.wsMessages += 1;

    // Traffic from a device proves its socket is alive. Without this the
    // presence keys — written once on connect with a 1h TTL — silently
    // expire under any call or session that outlives the TTL, and the
    // device stops being deliverable while still able to send.
    await this.touchPresence(client.userId, client.deviceId);
    const type = envelope.type as string;
    const roomId = (envelope as { roomId?: string }).roomId;
    const { targetDeviceId, payload } = envelope;
    if (!roomId || !type) return { error: 'invalid_envelope' };

    if (type === 'conf.join') {
      if (!(await this.conferenceService.canJoin(roomId, client.userId))) {
        return { error: 'not_allowed' };
      }
      const participants = await this.conferenceService.join(
        roomId,
        client.userId,
        client.deviceId,
      );
      for (const p of participants) {
        if (p.deviceId === client.deviceId) continue;
        await this.realtimeRegistry.deliverToDevice(p.deviceId, {
          type: 'conf.peer-joined',
          roomId,
          userId: client.userId,
          deviceId: client.deviceId,
        });
      }
      // Snapshot so the joiner can mesh with people already in the room.
      await this.realtimeRegistry.deliverToDevice(client.deviceId, {
        type: 'conf.joined',
        roomId,
        payload: { participants },
      });
      return { joined: true, participants };
    }

    if (type === 'conf.invite') {
      if (!(await this.conferenceService.canJoin(roomId, client.userId))) {
        return { error: 'not_allowed' };
      }
      const meta = await this.conferenceService.getMeta(roomId);
      if (!meta) return { error: 'not_found' };
      for (const userId of meta.allowed) {
        if (userId === client.userId) continue;
        const devices = await this.redis.sMembers(`ws:userdevices:${userId}`);
        for (const deviceId of devices) {
          await this.realtimeRegistry.deliverToDevice(deviceId, {
            type: 'conf.invite',
            roomId,
            fromUserId: client.userId,
            fromDeviceId: client.deviceId,
            payload: { title: meta.title || 'Group Call' },
          });
        }
      }
      return { invited: true };
    }

    if (!(await this.conferenceService.isMember(roomId, client.deviceId))) {
      return { error: 'not_member' };
    }

    if (type === 'conf.leave') {
      await this.conferenceService.leave(roomId, client.userId, client.deviceId);
      const members = await this.conferenceService.members(roomId);
      for (const p of members) {
        await this.realtimeRegistry.deliverToDevice(p.deviceId, {
          type: 'conf.peer-left',
          roomId,
          userId: client.userId,
          deviceId: client.deviceId,
        });
      }
      return { left: true };
    }

    // conf.offer / conf.answer / conf.ice — relay to a specific peer device.
    if (!targetDeviceId) return { error: 'target_required' };
    if (!(await this.conferenceService.isMember(roomId, targetDeviceId))) {
      return { error: 'invalid_target' };
    }
    const delivered = await this.realtimeRegistry.deliverToDevice(targetDeviceId, {
      type,
      roomId,
      fromUserId: client.userId,
      fromDeviceId: client.deviceId,
      payload,
    });
    return delivered
      ? { delivered: true }
      : { delivered: false, reason: 'peer_unreachable' };
  }

  /** Delivers a message to every live socket for a device; returns the count. */
  /** Re-arms the presence keys that gate cross-instance delivery. */
  private async touchPresence(userId: string, deviceId: string): Promise<void> {
    await this.redis.expire(`ws:device:${deviceId}`, PRESENCE_TTL_SECONDS);
    await this.redis.expire(`ws:user:${userId}`, PRESENCE_TTL_SECONDS);
    await this.redis.expire(`ws:userdevices:${userId}`, PRESENCE_TTL_SECONDS);
  }

  private deliverToDevice(
    deviceId: string,
    message: Record<string, unknown>,
  ): number {
    let count = 0;
    this.server.clients.forEach((ws) => {
      const sock = ws as unknown as AuthenticatedSocket;
      if (sock.deviceId === deviceId && sock.readyState === 1) {
        sock.send(JSON.stringify(message));
        count++;
      }
    });
    return count;
  }
}
