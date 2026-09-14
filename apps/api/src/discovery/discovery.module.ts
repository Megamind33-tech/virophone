import { Module } from '@nestjs/common';
import { DiscoveryService } from './discovery.service';
import { DiscoveryController } from './discovery.controller';
import { BlocksModule } from '../blocks/blocks.module';

@Module({
  imports: [BlocksModule],
  controllers: [DiscoveryController],
  providers: [DiscoveryService],
  exports: [DiscoveryService],
})
export class DiscoveryModule {}
