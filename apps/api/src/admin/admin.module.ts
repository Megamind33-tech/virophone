import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { JwtModule } from '@nestjs/jwt';
import { AdminController } from './admin.controller';
import { AdminService } from './admin.service';
import { AdminGuard } from './admin.guard';
import { User } from '../database/entities/user.entity';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Device } from '../database/entities/device.entity';
import { SecurityEvent } from '../database/entities/security-event.entity';

@Module({
  imports: [
    TypeOrmModule.forFeature([User, Profile, PhoneIdentity, Device, SecurityEvent]),
    JwtModule.register({
      secret: process.env.JWT_ACCESS_SECRET || 'dev_access_secret',
    }),
  ],
  controllers: [AdminController],
  providers: [AdminService, AdminGuard],
})
export class AdminModule {}
