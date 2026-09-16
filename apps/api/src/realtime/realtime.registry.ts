import { Injectable } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';

/** A sink that can deliver a JSON message to all live sockets of a device. */
export interface SocketSink {
  deliverToDevice(deviceId: string, message: Record<string, unknown>): number;
}

/**
 * Decouples message/event producers (e.g. MessagesService) from the WebSocket
 * gateway that owns the sockets. The gateway registers itself as the sink.
 *
 * Today delivery is in-process; Phase C swaps the sink for a Redis pub/sub
 * fan-out so any API replica can deliver to a socket held by another replica.
 */
@Injectable()
export class RealtimeRegistry {
  private sink: SocketSink | null = null;

  constructor(private readonly redis: RedisService) {}

  registerSink(sink: SocketSink): void {
    this.sink = sink;
  }

  deliverToDevice(deviceId: string, message: Record<string, unknown>): number {
    return this.sink?.deliverToDevice(deviceId, message) ?? 0;
  }

  /** Resolves the user's currently connected device and delivers to it. */
  async deliverToUser(
    userId: string,
    message: Record<string, unknown>,
  ): Promise<number> {
    const conn = await this.redis.getJson<{ deviceId: string }>(
      `ws:user:${userId}`,
    );
    if (!conn?.deviceId) return 0;
    return this.deliverToDevice(conn.deviceId, message);
  }
}
