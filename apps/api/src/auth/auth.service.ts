import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { JwtService } from '@nestjs/jwt';
import { v4 as uuidv4 } from 'uuid';
import { createHmac, randomInt } from 'crypto';
import { User } from '../database/entities/user.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Profile } from '../database/entities/profile.entity';
import { Device } from '../database/entities/device.entity';
import { Session } from '../database/entities/session.entity';
import { OtpChallenge } from '../database/entities/otp-challenge.entity';
import { ConsoleOtpProvider } from './otp/console-otp.provider';
import { ViroException } from '../common/exceptions/viro.exception';
import { normalizeE164, isValidE164 } from '../common/utils/phone.util';
import { hashPhoneForStorage, hashRefreshToken } from '../common/utils/hash.util';
import { SecurityService } from '../security/security.service';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class AuthService {
  private readonly otpProvider = new ConsoleOtpProvider();
  private readonly maxOtpAttempts = parseInt(process.env.OTP_MAX_ATTEMPTS || '5', 10);
  private readonly otpExpiresSeconds = parseInt(process.env.OTP_EXPIRES_SECONDS || '300', 10);

  constructor(
    @InjectRepository(User) private readonly userRepo: Repository<User>,
    @InjectRepository(PhoneIdentity) private readonly phoneRepo: Repository<PhoneIdentity>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    @InjectRepository(Session) private readonly sessionRepo: Repository<Session>,
    @InjectRepository(OtpChallenge) private readonly otpRepo: Repository<OtpChallenge>,
    private readonly jwtService: JwtService,
    private readonly securityService: SecurityService,
  ) {}

  async requestOtp(phoneE164: string): Promise<{ challengeId: string; expiresAt: string }> {
    const normalized = normalizeE164(phoneE164);
    if (!normalized) {
      throw new ViroException('INVALID_E164', 'Invalid phone number format.', HttpStatus.BAD_REQUEST);
    }

    const code =
      process.env.OTP_PROVIDER === 'test' && process.env.TEST_OTP_CODE
        ? process.env.TEST_OTP_CODE
        : String(randomInt(100000, 999999));
    const codeHash = this.hashOtp(code);
    const expiresAt = new Date(Date.now() + this.otpExpiresSeconds * 1000);

    const challenge = this.otpRepo.create({
      phoneE164: normalized,
      codeHash,
      expiresAt,
    });
    await this.otpRepo.save(challenge);
    await this.otpProvider.sendOtp(normalized, code);

    return { challengeId: challenge.id, expiresAt: expiresAt.toISOString() };
  }

  async verifyOtp(
    challengeId: string,
    code: string,
    devicePublicKey: string,
    platform: string,
    appVersion: string,
  ) {
    const challenge = await this.otpRepo.findOne({ where: { id: challengeId } });
    if (!challenge || challenge.verifiedAt) {
      throw new ViroException('VALIDATION_ERROR', 'Invalid or expired challenge.', HttpStatus.BAD_REQUEST);
    }
    if (new Date() > challenge.expiresAt) {
      throw new ViroException('VALIDATION_ERROR', 'OTP has expired.', HttpStatus.BAD_REQUEST);
    }
    if (challenge.attempts >= this.maxOtpAttempts) {
      throw new ViroException('VALIDATION_ERROR', 'Too many attempts.', HttpStatus.BAD_REQUEST);
    }

    challenge.attempts += 1;
    await this.otpRepo.save(challenge);

    if (challenge.codeHash !== this.hashOtp(code)) {
      await this.securityService.logEvent({
        eventType: 'INVALID_OTP_ATTEMPT',
        severity: 'LOW',
        metadata: { challengeId },
      });
      throw new ViroException('VALIDATION_ERROR', 'Invalid OTP code.', HttpStatus.BAD_REQUEST);
    }

    challenge.verifiedAt = new Date();
    await this.otpRepo.save(challenge);

    let phoneIdentity = await this.phoneRepo.findOne({
      where: { phoneE164: challenge.phoneE164 },
    });

    let isNewUser = false;
    let userId: string;

    if (!phoneIdentity) {
      isNewUser = true;
      userId = uuidv4();
      const salt = process.env.CONTACT_HASH_SALT || 'dev_contact_salt';
      const user = this.userRepo.create({ id: userId, status: 'ACTIVE' });
      await this.userRepo.save(user);

      phoneIdentity = this.phoneRepo.create({
        userId,
        phoneE164: challenge.phoneE164,
        phoneHash: hashPhoneForStorage(challenge.phoneE164, salt),
        verifiedAt: new Date(),
        status: 'VERIFIED',
      });
      await this.phoneRepo.save(phoneIdentity);

      const profile = this.profileRepo.create({ userId, displayName: '' });
      await this.profileRepo.save(profile);
    } else {
      userId = phoneIdentity.userId;
      phoneIdentity.verifiedAt = new Date();
      phoneIdentity.status = 'VERIFIED';
      await this.phoneRepo.save(phoneIdentity);
    }

    const device = this.deviceRepo.create({
      userId,
      publicKey: devicePublicKey,
      platform,
      appVersion,
    });
    await this.deviceRepo.save(device);

    const tokens = await this.createSession(userId, device.id);

    return {
      ...tokens,
      userId,
      deviceId: device.id,
      isNewUser,
    };
  }

  async refreshToken(refreshToken: string) {
    const tokenHash = hashRefreshToken(refreshToken);
    const session = await this.sessionRepo.findOne({
      where: { refreshTokenHash: tokenHash },
      relations: ['device'],
    });

    if (!session) {
      throw new ViroException('UNAUTHORIZED', 'Invalid refresh token.', HttpStatus.UNAUTHORIZED);
    }

    if (session.revokedAt) {
      await this.securityService.logEvent({
        userId: session.userId,
        deviceId: session.deviceId,
        eventType: 'REFRESH_TOKEN_REUSE',
        severity: 'CRITICAL',
      });
      await this.revokeSessionFamily(session.familyId);
      throw new ViroException('TOKEN_REUSE_DETECTED', 'Session revoked due to token reuse.', HttpStatus.UNAUTHORIZED);
    }

    if (new Date() > session.expiresAt) {
      throw new ViroException('TOKEN_EXPIRED', 'Refresh token expired.', HttpStatus.UNAUTHORIZED);
    }

    if (session.device?.revokedAt) {
      throw new ViroException('DEVICE_REVOKED', 'Device has been revoked.', HttpStatus.UNAUTHORIZED);
    }

    const user = await this.userRepo.findOne({ where: { id: session.userId } });
    if (!user || user.status !== 'ACTIVE') {
      throw new ViroException('UNAUTHORIZED', 'Account unavailable.', HttpStatus.UNAUTHORIZED);
    }

    session.revokedAt = new Date();
    await this.sessionRepo.save(session);

    return this.createSession(session.userId, session.deviceId, session.familyId);
  }

  async logout(userId: string, deviceId: string) {
    await this.sessionRepo.update(
      { userId, deviceId, revokedAt: null as unknown as undefined },
      { revokedAt: new Date() },
    );
  }

  private async createSession(userId: string, deviceId: string, familyId?: string) {
    const family = familyId || uuidv4();
    const refreshToken = uuidv4() + '.' + uuidv4();
    const refreshTokenHash = hashRefreshToken(refreshToken);
    const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);

    const session = this.sessionRepo.create({
      userId,
      deviceId,
      refreshTokenHash,
      familyId: family,
      expiresAt,
    });
    await this.sessionRepo.save(session);

    const accessToken = this.jwtService.sign(
      { sub: userId, deviceId },
      { expiresIn: process.env.JWT_ACCESS_EXPIRES_IN || '15m' },
    );

    return {
      accessToken,
      refreshToken,
      expiresIn: 900,
    };
  }

  private async revokeSessionFamily(familyId: string) {
    await this.sessionRepo.update(
      { familyId, revokedAt: null as unknown as undefined },
      { revokedAt: new Date() },
    );
  }

  private hashOtp(code: string): string {
    return createHmac('sha256', process.env.JWT_ACCESS_SECRET || 'dev')
      .update(code)
      .digest('hex');
  }
}
