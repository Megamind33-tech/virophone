import { Module } from '@nestjs/common';
import { TurnCredentialService } from './turn-credential.service';
import { TurnController } from './turn.controller';

@Module({
  controllers: [TurnController],
  providers: [TurnCredentialService],
  exports: [TurnCredentialService],
})
export class TurnModule {}
