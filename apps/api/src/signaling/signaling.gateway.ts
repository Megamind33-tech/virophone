import {
  WebSocketGateway,
  WebSocketServer,
  OnGatewayConnection,
  OnGatewayDisconnect,
  SubscribeMessage,
  MessageBody,
  ConnectedSocket,
} from '@nestjs/websockets';
import { OnModuleInit } from '@nestjs/common';
import { RealtimeRegistry } from '../realtime/realtime.registry';
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

interface AuthenticatedSocket extends WebSocket {
  userId?: string;
  deviceId?: string;
}

interface JwtPayload {
  sub: string;
  deviceId: string;
}

export type SignalingEventType =
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
  | 'call.error';

export interface SignalingEnvelope {
  type: SignalingEventType;
  callId: string;
  targetDeviceId?: string;
  payload?: unknown;
}

@WebSocketGateway({ path: '/api/v1/signaling/ws' })
export class SignalingGateway
  implements OnGatewayConnection, OnGatewayDisconnect, OnModuleInit
{
  @WebSocketServer()
  server!: Server;

  constructor(
    private readonly jwtService: JwtService,
    private readonly redis: RedisService,
    private readonly presenceService: PresenceService,
    private readonly callSessionService: CallSessionService,
    private readonly callsService: CallsService,
    private readonly realtimeRegistry: RealtimeRegistry,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    @InjectRepository(User) private readonly userRepo: Repository<User>,
  ) {}

  onModuleInit(): void {
    // Let server-side producers (messages, etc.) deliver frames over the sockets
    // this gateway owns.
    this.realtimeRegistry.registerSink({
      deliverToDevice: (deviceId, message) =>
        this.deliverToDevice(deviceId, message),
    });
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

    const { type, callId, targetDeviceId, payload } = envelope;
    if (!callId || !type) {
      return { error: 'invalid_envelope' };
    }

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

    let recipientDeviceId = targetDeviceId;
    if (!recipientDeviceId) {
      recipientDeviceId =
        client.deviceId === session.callerDeviceId
          ? session.calleeDeviceId
          : session.callerDeviceId;
    }

    const allowedRecipient =
      recipientDeviceId === session.callerDeviceId ||
      recipientDeviceId === session.calleeDeviceId;
    if (!allowedRecipient) {
      return { error: 'invalid_target' };
    }

    if (type === 'call.invite' && client.deviceId !== session.callerDeviceId) {
      return { error: 'only_caller_may_invite' };
    }
    if (type === 'call.answer' && client.deviceId !== session.calleeDeviceId) {
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

    // Deliver across instances via the realtime bus (works whether the peer's
    // socket is on this replica or another).
    const delivered = await this.realtimeRegistry.deliverToDevice(recipientDeviceId, {
      type,
      callId,
      fromUserId: client.userId,
      fromDeviceId: client.deviceId,
      payload,
    });

    if (!delivered) {
      // The peer's socket is gone (app closed / lost connection). Tell the
      // sender explicitly instead of leaving the call hanging in "connecting".
      return { delivered: false, reason: 'peer_unreachable' };
    }

    return { delivered: true };
  }

  /** Delivers a message to every live socket for a device; returns the count. */
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
