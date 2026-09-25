import { Module } from '@nestjs/common';
import { SignalsService } from './signals.service';
import { SignalsController } from './signals.controller';
import { PushModule } from '../push/push.module';
import { BlocksModule } from '../blocks/blocks.module';

@Module({
  imports: [PushModule, BlocksModule],
  controllers: [SignalsController],
  providers: [SignalsService],
  exports: [SignalsService],
})
export class SignalsModule {}
