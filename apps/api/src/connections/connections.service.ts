import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Block } from '../database/entities/block.entity';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class ConnectionsService {
  constructor(
    @InjectRepository(ViroConnection) private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
  ) {}

  async create(requesterId: string, targetUserId: string) {
    if (requesterId === targetUserId) {
      throw new ViroException('VALIDATION_ERROR', 'Cannot connect to yourself.', HttpStatus.BAD_REQUEST);
    }

    const blocked = await this.blockRepo.findOne({
      where: [
        { blockerUserId: targetUserId, blockedUserId: requesterId },
        { blockerUserId: requesterId, blockedUserId: targetUserId },
      ],
    });
    if (blocked) {
      throw new ViroException('CALL_TARGET_UNAVAILABLE', 'This person is currently unavailable.', HttpStatus.NOT_FOUND);
    }

    const existing = await this.connectionRepo.findOne({
      where: { requesterUserId: requesterId, recipientUserId: targetUserId },
    });
    if (existing) return existing;

    const connection = this.connectionRepo.create({
      requesterUserId: requesterId,
      recipientUserId: targetUserId,
      status: 'PENDING',
    });
    return this.connectionRepo.save(connection);
  }

  async accept(connectionId: string, userId: string) {
    const connection = await this.connectionRepo.findOne({ where: { id: connectionId } });
    if (!connection || connection.recipientUserId !== userId) {
      throw new ViroException('NOT_FOUND', 'Connection not found.', HttpStatus.NOT_FOUND);
    }
    connection.status = 'ACCEPTED';
    connection.acceptedAt = new Date();
    return this.connectionRepo.save(connection);
  }

  async reject(connectionId: string, userId: string) {
    const connection = await this.connectionRepo.findOne({ where: { id: connectionId } });
    if (!connection || connection.recipientUserId !== userId) {
      throw new ViroException('NOT_FOUND', 'Connection not found.', HttpStatus.NOT_FOUND);
    }
    connection.status = 'REJECTED';
    return this.connectionRepo.save(connection);
  }

  async revoke(connectionId: string, userId: string) {
    const connection = await this.connectionRepo.findOne({ where: { id: connectionId } });
    if (!connection) {
      throw new ViroException('NOT_FOUND', 'Connection not found.', HttpStatus.NOT_FOUND);
    }
    if (connection.requesterUserId !== userId && connection.recipientUserId !== userId) {
      throw new ViroException('FORBIDDEN', 'Not authorized.', HttpStatus.FORBIDDEN);
    }
    connection.status = 'REVOKED';
    return this.connectionRepo.save(connection);
  }
}
