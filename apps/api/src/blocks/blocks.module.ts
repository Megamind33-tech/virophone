import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { BlocksService } from './blocks.service';
import { BlocksController } from './blocks.controller';
import { Block } from '../database/entities/block.entity';
import { MomentsModule } from '../moments/moments.module';

@Module({
  imports: [TypeOrmModule.forFeature([Block]), MomentsModule],
  controllers: [BlocksController],
  providers: [BlocksService],
  exports: [BlocksService],
})
export class BlocksModule {}
