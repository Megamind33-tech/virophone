import { Injectable, OnModuleInit } from '@nestjs/common';
import { RedisService } from '../redis/redis.service';

/** A sink that can deliver a JSON message to all live sockets of a device. */
export interface SocketSink {
  deliverToDevice(deviceId: string, message: Record<string, unknown>): number;
}

const BUS_CHANNEL = 'realtime:deliver';

/** Must match the TTL the signaling gateway writes ws:device:{id} with. */
const PRESENCE_TTL_SECONDS = 3600;

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
   *
   * A live local socket is the authoritative answer and is tried FIRST. The
   * `ws:device:{id}` presence key is written once on connect with a 1h TTL and
   * is only a hint for *other* instances; gating on it alone meant that any
   * socket held open longer than that TTL became permanently undeliverable
   * while still being perfectly able to send. On a call that presents as the
   * caller never receiving `call.accept`, so it never joins the media room —
   * the call rings, connects, and carries no audio.
   */
  async deliverToDevice(
    deviceId: string,
    message: Record<string, unknown>,
  ): Promise<boolean> {
    const localCount = this.sink?.deliverToDevice(deviceId, message) ?? 0;
    if (localCount > 0) {
      // Proof of life: keep the cross-instance hint in step with reality.
      await this.redis.expire(`ws:device:${deviceId}`, PRESENCE_TTL_SECONDS);
      return true;
    }
    const online = await this.redis.exists(`ws:device:${deviceId}`);
    if (!online) return false;
    // Not ours, but some instance claims it — fan out. The frame comes back to
    // this instance too and finds no socket, so this cannot double-deliver.
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
