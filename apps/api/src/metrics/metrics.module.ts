import { Global, Injectable, Module } from '@nestjs/common';

@Injectable()
export class MetricsService {
  private readonly startedAt = Date.now();
  httpRequests = 0;
  httpErrors = 0;
  wsConnections = 0;
  wsMessages = 0;
  turnCredentials = 0;
  callsAuthorized = 0;
  messagesSent = 0;

  snapshot() {
    return {
      uptimeSeconds: Math.floor((Date.now() - this.startedAt) / 1000),
      http: { requests: this.httpRequests, errors: this.httpErrors },
      signaling: {
        wsConnections: this.wsConnections,
        wsMessages: this.wsMessages,
      },
      turn: { credentialsIssued: this.turnCredentials },
      calls: { authorized: this.callsAuthorized },
      messages: { sent: this.messagesSent },
      timestamp: new Date().toISOString(),
    };
  }
}

@Global()
@Module({
  providers: [MetricsService],
  exports: [MetricsService],
})
export class MetricsModule {}
