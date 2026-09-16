import { Injectable, OnModuleDestroy } from '@nestjs/common';
import Redis from 'ioredis';

@Injectable()
export class RedisService implements OnModuleDestroy {
  private readonly client: Redis;
  private subscriber: Redis | null = null;
  private readonly url: string;

  constructor() {
    this.url = process.env.REDIS_URL || 'redis://localhost:6379';
    this.client = new Redis(this.url, {
      maxRetriesPerRequest: 3,
      lazyConnect: process.env.NODE_ENV === 'test',
    });
  }

  getClient(): Redis {
    return this.client;
  }

  async exists(key: string): Promise<boolean> {
    return (await this.client.exists(key)) > 0;
  }

  async sAdd(key: string, member: string, ttlSeconds?: number): Promise<void> {
    await this.client.sadd(key, member);
    if (ttlSeconds) await this.client.expire(key, ttlSeconds);
  }

  async sRem(key: string, member: string): Promise<void> {
    await this.client.srem(key, member);
  }

  async sMembers(key: string): Promise<string[]> {
    return this.client.smembers(key);
  }

  async publish(channel: string, payload: unknown): Promise<void> {
    await this.client.publish(channel, JSON.stringify(payload));
  }

  /**
   * Subscribes to a channel on a dedicated connection (ioredis requires a
   * separate connection for subscribe mode). Handler receives parsed JSON.
   */
  async subscribe(
    channel: string,
    handler: (message: unknown) => void,
  ): Promise<void> {
    if (!this.subscriber) {
      this.subscriber = this.client.duplicate();
    }
    const sub = this.subscriber;
    await sub.subscribe(channel);
    sub.on('message', (ch, message) => {
      if (ch !== channel) return;
      try {
        handler(JSON.parse(message));
      } catch {
        /* ignore malformed bus messages */
      }
    });
  }

  async ping(): Promise<boolean> {
    try {
      return (await this.client.ping()) === 'PONG';
    } catch {
      return false;
    }
  }

  async setJson(key: string, value: unknown, ttlSeconds: number): Promise<void> {
    await this.client.set(key, JSON.stringify(value), 'EX', ttlSeconds);
  }

  async getJson<T>(key: string): Promise<T | null> {
    const raw = await this.client.get(key);
    if (!raw) return null;
    return JSON.parse(raw) as T;
  }

  async del(key: string): Promise<void> {
    await this.client.del(key);
  }

  async onModuleDestroy() {
    if (this.subscriber) {
      await this.subscriber.quit().catch(() => undefined);
    }
    await this.client.quit();
  }
}
