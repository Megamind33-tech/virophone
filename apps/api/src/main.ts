import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';
import { WsAdapter } from '@nestjs/platform-ws';
import { json } from 'express';
import { AppModule } from './app.module';
import { RequestIdMiddleware } from './common/middleware/request-id.middleware';
import { validateProductionConfig } from './config/production-config';

async function bootstrap() {
  validateProductionConfig();
  // Nest's built-in parser is disabled so JSON bodies get the larger limit
  // below; without this the express default of 100 KB applies.
  const app = await NestFactory.create(AppModule, { bodyParser: false });
  app.useWebSocketAdapter(new WsAdapter(app));

  // A sealed message carries one ciphertext per recipient device, and a single
  // send may carry up to 512 of them by the DTO's own contract — well past
  // Express's 100 KB JSON default, which refused real encrypted sends outright
  // ("request entity too large", surfacing to testers as a generic unexpected
  // error). 5 MB covers the DTO maximum with headroom; media uploads go
  // through their own multipart route and are unaffected by this limit.
  app.use(json({ limit: '5mb' }));

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
  // A film shared into a Moment can take a while to upload on mobile data.
  // Only the whole-request limit is raised; the headers timeout that stops
  // slow-header attacks stays as it is, and every body has a size limit.
  const server = app.getHttpServer();
  server.requestTimeout = 30 * 60 * 1000;
  console.log(`Viro Reach API listening on port ${port}`);
}

bootstrap();
