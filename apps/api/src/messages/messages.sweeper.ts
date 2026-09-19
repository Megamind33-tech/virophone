import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { MessagesService } from './messages.service';

/**
 * Runs the messaging clock: releases "send later" messages when due and
 * deletes disappearing messages and ended private sessions. Every step is a
 * single claim-or-delete statement, so running on several replicas at once
 * is safe.
 */
@Injectable()
export class MessagesSweeper implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger('MessagesSweeper');
  private timer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(private readonly messages: MessagesService) {}

  onModuleInit(): void {
    if (process.env.NODE_ENV === 'test' || process.env.MESSAGES_SWEEP_DISABLED === 'true') return;
    const everyMs = parseInt(process.env.MESSAGES_SWEEP_MS || '15000', 10);
    this.timer = setInterval(() => void this.tick(), everyMs);
  }

  onModuleDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async tick(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      await this.messages.sweep();
    } catch (e) {
      this.logger.warn(`sweep failed: ${(e as Error).message}`);
    } finally {
      this.running = false;
    }
  }
}
