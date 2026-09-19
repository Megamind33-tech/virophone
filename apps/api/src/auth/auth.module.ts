import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PassportModule } from '@nestjs/passport';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AuthService } from './auth.service';
import { AuthController } from './auth.controller';
import { JwtStrategy } from './strategies/jwt.strategy';
import { User } from '../database/entities/user.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Profile } from '../database/entities/profile.entity';
import { Device } from '../database/entities/device.entity';
import { Session } from '../database/entities/session.entity';
import { OtpChallenge } from '../database/entities/otp-challenge.entity';
import { EmailIdentity } from '../database/entities/email-identity.entity';
import { FirebaseAuthService } from './firebase-auth.service';
import { SecurityModule } from '../security/security.module';

@Module({
  imports: [
    PassportModule.register({ defaultStrategy: 'jwt' }),
    JwtModule.register({
      secret: process.env.JWT_ACCESS_SECRET || 'dev_access_secret',
      signOptions: { expiresIn: process.env.JWT_ACCESS_EXPIRES_IN || '15m' },
    }),
    TypeOrmModule.forFeature([
      User, PhoneIdentity, Profile, Device, Session, OtpChallenge, EmailIdentity,
    ]),
    SecurityModule,
  ],
  controllers: [AuthController],
  providers: [AuthService, JwtStrategy, FirebaseAuthService],
  exports: [AuthService, JwtModule],
})
export class AuthModule {}
