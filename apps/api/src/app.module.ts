import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { ThrottlerModule } from '@nestjs/throttler';
import { AuthModule } from './auth/auth.module';
import { DevicesModule } from './devices/devices.module';
import { UsersModule } from './users/users.module';
import { ContactsModule } from './contacts/contacts.module';
import { DirectoryModule } from './directory/directory.module';
import { ConnectionsModule } from './connections/connections.module';
import { BlocksModule } from './blocks/blocks.module';
import { CallsModule } from './calls/calls.module';
import { HealthModule } from './health/health.module';
import { SecurityModule } from './security/security.module';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    ThrottlerModule.forRoot([
      {
        ttl: parseInt(process.env.RATE_LIMIT_TTL_SECONDS || '60', 10) * 1000,
        limit: parseInt(process.env.RATE_LIMIT_MAX_REQUESTS || '100', 10),
      },
    ]),
    TypeOrmModule.forRoot({
      type: 'postgres',
      url: process.env.DATABASE_URL || 'postgresql://viro:viro_dev_password@localhost:5432/viro_reach',
      autoLoadEntities: true,
      synchronize: false,
      logging: process.env.NODE_ENV === 'development',
    }),
    AuthModule,
    DevicesModule,
    UsersModule,
    ContactsModule,
    DirectoryModule,
    ConnectionsModule,
    BlocksModule,
    CallsModule,
    HealthModule,
    SecurityModule,
  ],
})
export class AppModule {}
