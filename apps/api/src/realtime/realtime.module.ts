import { Global, Module } from '@nestjs/common';
import { RealtimeRegistry } from './realtime.registry';

@Global()
@Module({
  providers: [RealtimeRegistry],
  exports: [RealtimeRegistry],
})
export class RealtimeModule {}
