import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { SecurityEvent } from '../database/entities/security-event.entity';
import { SecurityService } from './security.service';

@Module({
  imports: [TypeOrmModule.forFeature([SecurityEvent])],
  providers: [SecurityService],
  exports: [SecurityService],
})
export class SecurityModule {}
