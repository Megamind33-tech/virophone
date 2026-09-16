import { Injectable, OnModuleInit } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';

/** A sink that can deliver a JSON message to all live sockets of a device. */
export interface SocketSink {
  deliverToDevice(deviceId: string, message: Record<string, unknown>): number;
}

const BUS_CHANNEL = 'realtime:deliver';

interface BusFrame {
  deviceId: string;
  message: Record<string, unknown>;
}

/**
 * Decouples event producers (messages, call signaling) from the WebSocket
 * gateway that owns the sockets, and makes delivery work across API replicas.
 *
 * Delivery is fanned out over a Redis pub/sub channel: every instance receives
 * each frame and delivers it only if it holds the target socket locally. A
 * device is "reachable" when its `ws:device:{id}` presence key exists in Redis
 * (set by the gateway on connect), which lets producers decide whether to fall
 * back to push — correctly across instances.
 */
@Injectable()
export class RealtimeRegistry implements OnModuleInit {
  private sink: SocketSink | null = null;

  constructor(private readonly redis: RedisService) {}

  async onModuleInit(): Promise<void> {
    await this.redis.subscribe(BUS_CHANNEL, (raw) => {
      const frame = raw as BusFrame;
      if (frame?.deviceId && frame.message) {
        this.sink?.deliverToDevice(frame.deviceId, frame.message);
      }
    });
  }

  registerSink(sink: SocketSink): void {
    this.sink = sink;
  }

  /**
   * Delivers to a device if it is connected to any instance. Returns whether
   * the device was reachable (connected), not whether the socket write flushed.
   */
  async deliverToDevice(
    deviceId: string,
    message: Record<string, unknown>,
  ): Promise<boolean> {
    const online = await this.redis.exists(`ws:device:${deviceId}`);
    if (!online) return false;
    await this.redis.publish(BUS_CHANNEL, { deviceId, message } as BusFrame);
    return true;
  }

  /** Fans out to every connected device of a user. Returns true if any reachable. */
  async deliverToUser(
    userId: string,
    message: Record<string, unknown>,
  ): Promise<boolean> {
    const devices = await this.redis.sMembers(`ws:userdevices:${userId}`);
    if (devices.length === 0) return false;
    let anyReachable = false;
    for (const deviceId of devices) {
      const reachable = await this.deliverToDevice(deviceId, message);
      anyReachable = anyReachable || reachable;
    }
    return anyReachable;
  }
}
