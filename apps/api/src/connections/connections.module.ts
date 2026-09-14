import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { ConnectionsService } from './connections.service';
import { ConnectionsController } from './connections.controller';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Block } from '../database/entities/block.entity';

@Module({
  imports: [TypeOrmModule.forFeature([ViroConnection, Block])],
  controllers: [ConnectionsController],
  providers: [ConnectionsService],
  exports: [ConnectionsService],
})
export class ConnectionsModule {}
