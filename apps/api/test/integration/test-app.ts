import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import { WsAdapter } from '@nestjs/platform-ws';
import { AppModule } from '../../src/app.module';
import { Throttle } from '@nestjs/throttler';
import { AuthController } from '../../src/auth/auth.controller';

export async function createTestApp(options: { manyOtpFixtures?: boolean } = {}): Promise<INestApplication> {
  if (options.manyOtpFixtures) {
    // These suites create many accounts from one test-runner IP. Override only
    // their OTP fixture limits; production metadata and other rate-limit tests
    // are unchanged (Jest isolates each suite's module registry).
    for (const name of ['requestOtp', 'verifyOtp'] as const) {
      Throttle({ default: { limit: 10000, ttl: 300000 } })(
        AuthController.prototype, name,
        Object.getOwnPropertyDescriptor(AuthController.prototype, name)!,
      );
    }
  }
  const moduleFixture: TestingModule = await Test.createTestingModule({
    imports: [AppModule],
  }).compile();

  const app = moduleFixture.createNestApplication();
  app.useWebSocketAdapter(new WsAdapter(app));
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  await app.init();
  return app;
}
