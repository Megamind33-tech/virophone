import { Global, Module } from '@nestjs/common';
import { SignalingDeliveryService } from './signaling-delivery.service';

@Global()
@Module({
  providers: [SignalingDeliveryService],
  exports: [SignalingDeliveryService],
})
export class SignalingDeliveryModule {}
