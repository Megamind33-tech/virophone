import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';
import { WsAdapter } from '@nestjs/platform-ws';
import { AppModule } from './app.module';
import { RequestIdMiddleware } from './common/middleware/request-id.middleware';
import { validateProductionConfig } from './config/production-config';

async function bootstrap() {
  validateProductionConfig();
  const app = await NestFactory.create(AppModule);
  app.useWebSocketAdapter(new WsAdapter(app));

  app.use(new RequestIdMiddleware().use.bind(new RequestIdMiddleware()));
  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      forbidNonWhitelisted: true,
      transform: true,
    }),
  );

  const port = process.env.API_PORT || 3001;
  await app.listen(port);
  console.log(`Viro Reach API listening on port ${port}`);
}

bootstrap();
