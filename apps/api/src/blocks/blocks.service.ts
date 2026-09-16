import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Block } from '../database/entities/block.entity';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class BlocksService {
  constructor(
    @InjectRepository(Block) private readonly blockRepo: Repository<Block>,
  ) {}

  async list(blockerId: string) {
    const rows = await this.blockRepo.find({
      where: { blockerUserId: blockerId },
    });
    return rows.map((b) => ({ blockedUserId: b.blockedUserId }));
  }

  async block(blockerId: string, blockedUserId: string) {
    if (blockerId === blockedUserId) {
      throw new ViroException('VALIDATION_ERROR', 'Cannot block yourself.', HttpStatus.BAD_REQUEST);
    }

    const block = this.blockRepo.create({ blockerUserId: blockerId, blockedUserId });
    await this.blockRepo.save(block);
    return { success: true };
  }

  async unblock(blockerId: string, blockedUserId: string) {
    await this.blockRepo.delete({ blockerUserId: blockerId, blockedUserId });
    return { success: true };
  }

  async isBlocked(userA: string, userB: string): Promise<boolean> {
    const block = await this.blockRepo.findOne({
      where: [
        { blockerUserId: userA, blockedUserId: userB },
        { blockerUserId: userB, blockedUserId: userA },
      ],
    });
    return !!block;
  }
}
