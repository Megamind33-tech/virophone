import {
  WebSocketGateway,
  WebSocketServer,
  OnGatewayConnection,
  OnGatewayDisconnect,
  SubscribeMessage,
  MessageBody,
  ConnectedSocket,
} from '@nestjs/websockets';
import { Server } from 'ws';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Device } from '../database/entities/device.entity';
import { User } from '../database/entities/user.entity';
import { RedisService } from '../redis/redis.service';
import { PresenceService } from '../presence/presence.service';

interface AuthenticatedSocket extends WebSocket {
  userId?: string;
  deviceId?: string;
  isAlive?: boolean;
}

interface JwtPayload {
  sub: string;
  deviceId: string;
}

@WebSocketGateway({ path: '/api/v1/signaling/ws' })
export class SignalingGateway implements OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  server!: Server;

  constructor(
    private readonly jwtService: JwtService,
    private readonly redis: RedisService,
    private readonly presenceService: PresenceService,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    @InjectRepository(User) private readonly userRepo: Repository<User>,
  ) {}

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
      client.isAlive = true;

      await this.redis.setJson(
        `ws:device:${payload.deviceId}`,
        { userId: payload.sub, connectedAt: Date.now() },
        3600,
      );
      await this.presenceService.setPresence(payload.sub, 'ONLINE');
    } catch {
      client.close(4001, 'Unauthorized');
    }
  }

  async handleDisconnect(client: AuthenticatedSocket) {
    if (client.deviceId) {
      await this.redis.del(`ws:device:${client.deviceId}`);
    }
    if (client.userId) {
      await this.presenceService.setPresence(client.userId, 'OFFLINE');
    }
  }

  @SubscribeMessage('signal')
  async handleSignal(
    @ConnectedSocket() client: AuthenticatedSocket,
    @MessageBody() data: { callId: string; targetDeviceId: string; payload: unknown },
  ) {
    if (!client.userId || !client.deviceId) return { error: 'unauthorized' };

    const targetConn = await this.redis.getJson<{ userId: string }>(`ws:device:${data.targetDeviceId}`);
    if (!targetConn) {
      return { error: 'target_offline' };
    }

    // Relay only to connected devices; full call authorization verified at /calls/authorize
    this.server.clients.forEach((ws) => {
      const sock = ws as unknown as AuthenticatedSocket;
      if (sock.deviceId === data.targetDeviceId && sock.readyState === 1) {
        sock.send(JSON.stringify({
          type: 'signal',
          callId: data.callId,
          fromUserId: client.userId,
          fromDeviceId: client.deviceId,
          payload: data.payload,
        }));
      }
    });

    return { delivered: true };
  }
}
