import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { BlocksModule } from '../blocks/blocks.module';
import { OfflineTrustService } from './offline-trust.service';
import { OfflineTrustController } from './offline-trust.controller';

@Module({
  imports: [TypeOrmModule.forFeature([ContactMatch, ViroConnection]), BlocksModule],
  controllers: [OfflineTrustController],
  providers: [OfflineTrustService],
  exports: [OfflineTrustService],
})
export class OfflineTrustModule {}
