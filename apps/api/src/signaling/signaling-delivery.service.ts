import { Injectable } from '@nestjs/common';

export interface SignalingOutboundMessage {
  type: string;
  callId: string;
  fromUserId?: string;
  fromDeviceId?: string;
  payload?: unknown;
}

type DeliverFn = (deviceId: string, message: SignalingOutboundMessage) => boolean;

@Injectable()
export class SignalingDeliveryService {
  private deliverFn: DeliverFn | null = null;

  registerDeliverer(fn: DeliverFn) {
    this.deliverFn = fn;
  }

  deliverToDevice(deviceId: string, message: SignalingOutboundMessage): boolean {
    return this.deliverFn?.(deviceId, message) ?? false;
  }

  notifyIncomingCall(params: {
    calleeDeviceId: string;
    callId: string;
    callerUserId: string;
    callerDeviceId: string;
    callerPhoneE164?: string;
    callerDisplayName?: string;
  }): boolean {
    const payload: Record<string, string> = {};
    if (params.callerPhoneE164) payload.callerPhoneE164 = params.callerPhoneE164;
    if (params.callerDisplayName) payload.callerDisplayName = params.callerDisplayName;
    return this.deliverToDevice(params.calleeDeviceId, {
      type: 'call.incoming',
      callId: params.callId,
      fromUserId: params.callerUserId,
      fromDeviceId: params.callerDeviceId,
      payload: Object.keys(payload).length > 0 ? payload : undefined,
    });
  }
}
