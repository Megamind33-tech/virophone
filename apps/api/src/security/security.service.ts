import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { SecurityEvent } from '../database/entities/security-event.entity';

@Injectable()
export class SecurityService {
  constructor(
    @InjectRepository(SecurityEvent)
    private readonly eventRepo: Repository<SecurityEvent>,
  ) {}

  async logEvent(params: {
    userId?: string;
    deviceId?: string;
    eventType: string;
    severity: string;
    metadata?: Record<string, unknown>;
  }) {
    const event = this.eventRepo.create({
      userId: params.userId || null,
      deviceId: params.deviceId || null,
      eventType: params.eventType,
      severity: params.severity,
      metadata: params.metadata || null,
    });
    await this.eventRepo.save(event);
  }
}
