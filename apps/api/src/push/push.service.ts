import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import { PushToken } from '../database/entities/push-token.entity';
import { PushPayload, PushSender, createPushSender } from './push-sender';

@Injectable()
export class PushService {
  private readonly logger = new Logger('PushService');
  private readonly sender: PushSender = createPushSender();

  constructor(
    @InjectRepository(PushToken)
    private readonly tokenRepo: Repository<PushToken>,
  ) {}

  /** Idempotently registers a push token, re-homing it to the current device/user. */
  async registerToken(
    userId: string,
    deviceId: string | null,
    token: string,
    provider = 'fcm',
  ): Promise<void> {
    const existing = await this.tokenRepo.findOne({ where: { token } });
    if (existing) {
      existing.userId = userId;
      existing.deviceId = deviceId;
      existing.provider = provider;
      await this.tokenRepo.save(existing);
      return;
    }
    await this.tokenRepo.save(
      this.tokenRepo.create({ userId, deviceId, token, provider }),
    );
  }

  async removeToken(token: string): Promise<void> {
    await this.tokenRepo.delete({ token });
  }

  async tokensForUser(userId: string): Promise<string[]> {
    const rows = await this.tokenRepo.find({ where: { userId } });
    return rows.map((r) => r.token);
  }

  async tokensForUsers(userIds: string[]): Promise<string[]> {
    if (userIds.length === 0) return [];
    const rows = await this.tokenRepo.find({ where: { userId: In(userIds) } });
    return rows.map((r) => r.token);
  }

  /** Best-effort push to all of a user's devices. Never throws to callers. */
  async sendToUser(userId: string, payload: PushPayload): Promise<void> {
    try {
      const tokens = await this.tokensForUser(userId);
      if (tokens.length === 0) return;
      const result = await this.sender.send(tokens, payload);
      await this.pruneDeadTokens(result.invalidTokens);
    } catch (e) {
      this.logger.warn(`push to user ${userId} failed: ${(e as Error).message}`);
    }
  }

  async sendToUsers(userIds: string[], payload: PushPayload): Promise<void> {
    try {
      const tokens = await this.tokensForUsers(userIds);
      if (tokens.length === 0) return;
      const result = await this.sender.send(tokens, payload);
      await this.pruneDeadTokens(result.invalidTokens);
    } catch (e) {
      this.logger.warn(`push to users failed: ${(e as Error).message}`);
    }
  }

  /**
   * Deletes tokens FCM has told us are permanently dead. Without this they are
   * retried on every send forever, and a user who has reinstalled a few times
   * accumulates rows that can never be delivered to — the same slow rot that
   * left stale device ids fanning out call invites to nothing.
   */
  private async pruneDeadTokens(tokens: string[]): Promise<void> {
    if (tokens.length === 0) return;
    for (const token of tokens) {
      await this.tokenRepo.delete({ token }).catch(() => undefined);
    }
    this.logger.log(`pruned ${tokens.length} dead push token(s)`);
  }
}
