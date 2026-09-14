import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { DirectoryService } from './directory.service';
import { DirectoryController } from './directory.controller';
import { Profile } from '../database/entities/profile.entity';
import { Block } from '../database/entities/block.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';

@Module({
  imports: [TypeOrmModule.forFeature([Profile, Block, ViroConnection])],
  controllers: [DirectoryController],
  providers: [DirectoryService],
  exports: [DirectoryService],
})
export class DirectoryModule {}
