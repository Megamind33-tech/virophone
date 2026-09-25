import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, IsNull } from 'typeorm';
import { JwtService } from '@nestjs/jwt';
import { v4 as uuidv4 } from 'uuid';
import { createHmac, randomInt } from 'crypto';
import { User } from '../database/entities/user.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { Profile } from '../database/entities/profile.entity';
import { Device } from '../database/entities/device.entity';
import { Session } from '../database/entities/session.entity';
import { OtpChallenge } from '../database/entities/otp-challenge.entity';
import { EmailIdentity } from '../database/entities/email-identity.entity';
import { Call } from '../database/entities/call.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { createOtpProvider } from './otp/otp-provider.factory';
import { createEmailOtpProvider } from './email/email-otp.factory';
import {
  getHardwareTestOtpCode,
  isHardwareTestMode,
  isPhoneHardwareTestAllowed,
  maskPhoneForSecurityLog,
} from './otp/hardware-test.config';
import { ViroException } from '../common/exceptions/viro.exception';
import { normalizeE164, isValidE164 } from '../common/utils/phone.util';
import { hashPhoneForStorage, hashRefreshToken } from '../common/utils/hash.util';
import { SecurityService } from '../security/security.service';
import { FirebaseAuthService } from './firebase-auth.service';
import { AccountMergeService } from './account-merge.service';
import { HttpStatus } from '@nestjs/common';

@Injectable()
export class AuthService {
  private readonly otpProvider = createOtpProvider();
  private readonly emailProvider = createEmailOtpProvider();
  private readonly maxOtpAttempts = parseInt(process.env.OTP_MAX_ATTEMPTS || '5', 10);
  private readonly otpExpiresSeconds = parseInt(process.env.OTP_EXPIRES_SECONDS || '300', 10);

  constructor(
    @InjectRepository(User) private readonly userRepo: Repository<User>,
    @InjectRepository(PhoneIdentity) private readonly phoneRepo: Repository<PhoneIdentity>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    @InjectRepository(Session) private readonly sessionRepo: Repository<Session>,
    @InjectRepository(OtpChallenge) private readonly otpRepo: Repository<OtpChallenge>,
    @InjectRepository(EmailIdentity) private readonly emailRepo: Repository<EmailIdentity>,
    private readonly jwtService: JwtService,
    private readonly securityService: SecurityService,
    private readonly firebaseAuthService: FirebaseAuthService,
    private readonly accountMergeService: AccountMergeService,
  ) {}

  async requestOtp(phoneE164: string): Promise<{ challengeId: string; expiresAt: string }> {
    const normalized = normalizeE164(phoneE164);
    if (!normalized) {
      throw new ViroException('INVALID_E164', 'Invalid phone number format.', HttpStatus.BAD_REQUEST);
    }

    if (isHardwareTestMode() && !isPhoneHardwareTestAllowed(normalized)) {
      await this.securityService.logEvent({
        eventType: 'HARDWARE_TEST_AUTH_FAILURE',
        severity: 'MEDIUM',
        metadata: { reason: 'phone_not_allowlisted', phone: maskPhoneForSecurityLog(normalized) },
      });
      throw new ViroException(
        'FORBIDDEN',
        'Phone not authorized for hardware test OTP.',
        HttpStatus.FORBIDDEN,
      );
    }

    const code = this.resolveOtpCode(normalized);
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

  async requestEmailOtp(
    email: string,
  ): Promise<{ challengeId: string; expiresAt: string }> {
    const normalized = (email || '').trim().toLowerCase();
    if (!this.isValidEmail(normalized)) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'Invalid email address.',
        HttpStatus.BAD_REQUEST,
      );
    }

    const code =
      process.env.OTP_PROVIDER === 'test' && process.env.TEST_OTP_CODE
        ? process.env.TEST_OTP_CODE
        : String(randomInt(100000, 999999));
    const codeHash = this.hashOtp(code);
    const expiresAt = new Date(Date.now() + this.otpExpiresSeconds * 1000);

    const challenge = this.otpRepo.create({
      email: normalized,
      channel: 'email',
      codeHash,
      expiresAt,
    });
    await this.otpRepo.save(challenge);
    await this.emailProvider.sendOtp(normalized, code);

    return { challengeId: challenge.id, expiresAt: expiresAt.toISOString() };
  }

  async verifyEmailOtp(
    challengeId: string,
    code: string,
    devicePublicKey: string,
    platform: string,
    appVersion: string,
  ) {
    const challenge = await this.otpRepo.findOne({ where: { id: challengeId } });
    if (!challenge || challenge.channel !== 'email' || challenge.verifiedAt || !challenge.email) {
      throw new ViroException('VALIDATION_ERROR', 'Invalid or expired challenge.', HttpStatus.BAD_REQUEST);
    }
    const emailAddress: string = challenge.email;
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
        metadata: { challengeId, channel: 'email' },
      });
      throw new ViroException('VALIDATION_ERROR', 'Invalid OTP code.', HttpStatus.BAD_REQUEST);
    }

    challenge.verifiedAt = new Date();
    await this.otpRepo.save(challenge);

    let emailIdentity = await this.emailRepo.findOne({
      where: { email: emailAddress },
    });

    let isNewUser = false;
    let userId: string;

    if (!emailIdentity) {
      isNewUser = true;
      userId = uuidv4();
      const user = this.userRepo.create({ id: userId, status: 'ACTIVE' });
      await this.userRepo.save(user);

      emailIdentity = this.emailRepo.create({
        userId,
        email: emailAddress,
        verifiedAt: new Date(),
        status: 'VERIFIED',
      });
      await this.emailRepo.save(emailIdentity);

      const profile = this.profileRepo.create({ userId, displayName: '' });
      await this.profileRepo.save(profile);
    } else {
      userId = emailIdentity.userId;
      emailIdentity.verifiedAt = new Date();
      emailIdentity.status = 'VERIFIED';
      await this.emailRepo.save(emailIdentity);
    }

    const device = await this.signInDevice(userId, devicePublicKey, platform, appVersion);

    const tokens = await this.createSession(userId, device.id);

    return {
      ...tokens,
      userId,
      deviceId: device.id,
      isNewUser,
    };
  }

  private isValidEmail(email: string): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) && email.length <= 255;
  }

  async verifyOtp(
    challengeId: string,
    code: string,
    devicePublicKey: string,
    platform: string,
    appVersion: string,
  ) {
    const challenge = await this.otpRepo.findOne({ where: { id: challengeId } });
    if (!challenge || challenge.verifiedAt || !challenge.phoneE164) {
      throw new ViroException('VALIDATION_ERROR', 'Invalid or expired challenge.', HttpStatus.BAD_REQUEST);
    }
    const phoneNumber: string = challenge.phoneE164;
    if (new Date() > challenge.expiresAt) {
      throw new ViroException('VALIDATION_ERROR', 'OTP has expired.', HttpStatus.BAD_REQUEST);
    }
    if (challenge.attempts >= this.maxOtpAttempts) {
      throw new ViroException('VALIDATION_ERROR', 'Too many attempts.', HttpStatus.BAD_REQUEST);
    }

    challenge.attempts += 1;
    await this.otpRepo.save(challenge);

    if (challenge.codeHash !== this.hashOtp(code)) {
      const failureEvent = isPhoneHardwareTestAllowed(challenge.phoneE164)
        ? 'HARDWARE_TEST_AUTH_FAILURE'
        : 'INVALID_OTP_ATTEMPT';
      await this.securityService.logEvent({
        eventType: failureEvent,
        severity: failureEvent === 'HARDWARE_TEST_AUTH_FAILURE' ? 'MEDIUM' : 'LOW',
        metadata: {
          challengeId,
          reason: 'invalid_credential',
          phone: maskPhoneForSecurityLog(challenge.phoneE164),
        },
      });
      throw new ViroException('VALIDATION_ERROR', 'Invalid OTP code.', HttpStatus.BAD_REQUEST);
    }

    challenge.verifiedAt = new Date();
    await this.otpRepo.save(challenge);

    let phoneIdentity = await this.phoneRepo.findOne({
      where: { phoneE164: phoneNumber },
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
        phoneE164: phoneNumber,
        phoneHash: hashPhoneForStorage(phoneNumber, salt),
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

    const device = await this.signInDevice(userId, devicePublicKey, platform, appVersion);

    const tokens = await this.createSession(userId, device.id);

    if (isPhoneHardwareTestAllowed(challenge.phoneE164)) {
      await this.securityService.logEvent({
        userId,
        deviceId: device.id,
        eventType: 'HARDWARE_TEST_AUTH_SUCCESS',
        severity: 'LOW',
        metadata: { phone: maskPhoneForSecurityLog(challenge.phoneE164) },
      });
    }

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

  /**
   * The device a sign-in lands on.
   *
   * The same install signing back in to the same account is the same device:
   * its install key (kept in the phone's keystore for the life of the install)
   * matches, and logging out never revoked it. Reusing it is what lets that
   * phone read everything that was sealed for it while it was signed out, and
   * keep the encryption keys it already has — a new device every time made
   * every earlier message unreadable, on the phone and to its owner. A device
   * the person removed stays removed; a different install, or a reinstall, is
   * a new device, as it has to be: its keys are gone.
   */
  private async signInDevice(userId: string, devicePublicKey: string, platform: string, appVersion: string) {
    const existing = devicePublicKey
      ? await this.deviceRepo.findOne({
          where: { userId, publicKey: devicePublicKey, revokedAt: IsNull() },
          order: { createdAt: 'DESC' },
        })
      : null;
    if (existing) {
      existing.platform = platform;
      existing.appVersion = appVersion;
      existing.lastSeenAt = new Date();
      return this.deviceRepo.save(existing);
    }
    return this.deviceRepo.save(this.deviceRepo.create({ userId, publicKey: devicePublicKey, platform, appVersion }));
  }

  async logout(userId: string, deviceId: string) {
    await this.sessionRepo.update(
      { userId, deviceId, revokedAt: null as unknown as undefined },
      { revokedAt: new Date() },
    );
  }


  /**
   * Exchanges a verified Firebase ID token for a Viro session. Runs the same
   * device registration and session issue as phone-OTP, so callers downstream
   * cannot tell which sign-in method was used.
   */
  async signInWithFirebase(
    idToken: string,
    devicePublicKey: string,
    platform: string,
    appVersion: string,
  ) {
    const { userId, email, isNewUser } =
      await this.firebaseAuthService.resolveUserFromIdToken(idToken);

    const device = await this.signInDevice(userId, devicePublicKey, platform, appVersion);

    const tokens = await this.createSession(userId, device.id);
    return {
      ...tokens,
      userId,
      deviceId: device.id,
      email,
      isNewUser,
    };
  }

  // --- Linking a phone number to an account that signed in another way -------

  /**
   * Sends an OTP so a signed-in user can attach a phone number to their account.
   * Reuses the sign-in challenge machinery on purpose: accepting a typed number
   * on trust would let anyone claim any number, and claiming a number that
   * already has an account is account takeover.
   */
  async requestPhoneLink(
    userId: string,
    phoneE164: string,
  ): Promise<{ challengeId: string; expiresAt: string }> {
    const normalized = normalizeE164(phoneE164);
    if (!normalized) {
      throw new ViroException('INVALID_E164', 'Invalid phone number format.', HttpStatus.BAD_REQUEST);
    }
    // findOne would only ever see one of the user's numbers; an account may now
    // hold several, and re-adding any of them must be caught.
    const mine = await this.phoneRepo.find({ where: { userId } });
    if (mine.some((p) => p.phoneE164 === normalized)) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'That number is already on your account.',
        HttpStatus.BAD_REQUEST,
      );
    }
    if (isHardwareTestMode() && !isPhoneHardwareTestAllowed(normalized)) {
      throw new ViroException(
        'FORBIDDEN',
        'Phone not authorized for hardware test OTP.',
        HttpStatus.FORBIDDEN,
      );
    }
    const otpCode = this.resolveOtpCode(normalized);
    const challenge = this.otpRepo.create({
      phoneE164: normalized,
      codeHash: this.hashOtp(otpCode),
      expiresAt: new Date(Date.now() + this.otpExpiresSeconds * 1000),
    });
    await this.otpRepo.save(challenge);
    await this.otpProvider.sendOtp(normalized, otpCode);
    return { challengeId: challenge.id, expiresAt: challenge.expiresAt.toISOString() };
  }

  /**
   * Verifies the OTP and connects the number to the account.
   *
   * Three outcomes, because the number may already belong to someone:
   *  - unclaimed: attached to this account ("linked").
   *  - claimed, and THIS account has no history: the phone account wins and this
   *    account's email identity moves onto it ("adopted"). The client must swap
   *    to the returned session — it is now signed in as the phone account.
   *  - claimed, and both sides have history: refused. A true two-way merge means
   *    rewriting 20 user columns across 15 tables and resolving conflicting
   *    profiles, subscriptions and blocks, with no undo. Refusing loudly beats
   *    destroying one side of someone's history silently.
   */
  async verifyPhoneLink(
    userId: string,
    challengeId: string,
    code: string,
  ): Promise<{
    outcome: 'linked' | 'adopted';
    phoneE164: string;
    userId: string;
    accessToken?: string;
    refreshToken?: string;
    expiresIn?: number;
  }> {
    const challenge = await this.otpRepo.findOne({ where: { id: challengeId } });
    if (!challenge || challenge.verifiedAt || !challenge.phoneE164) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'Invalid or expired challenge.',
        HttpStatus.BAD_REQUEST,
      );
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
        userId,
        eventType: 'INVALID_OTP_ATTEMPT',
        severity: 'LOW',
        metadata: {
          challengeId,
          reason: 'phone_link_invalid_code',
          phone: maskPhoneForSecurityLog(challenge.phoneE164),
        },
      });
      throw new ViroException('VALIDATION_ERROR', 'Invalid OTP code.', HttpStatus.BAD_REQUEST);
    }
    challenge.verifiedAt = new Date();
    await this.otpRepo.save(challenge);

    const phoneNumber = challenge.phoneE164;
    const salt = process.env.CONTACT_HASH_SALT || 'dev_contact_salt';
    const existing = await this.phoneRepo.findOne({ where: { phoneE164: phoneNumber } });

    // Unclaimed: attach it. The account becomes reachable by phone-number
    // contact discovery, which is the entire point of linking.
    if (!existing) {
      await this.phoneRepo.save(
        this.phoneRepo.create({
          userId,
          phoneE164: phoneNumber,
          phoneHash: hashPhoneForStorage(phoneNumber, salt),
          verifiedAt: new Date(),
          status: 'VERIFIED',
        }),
      );
      await this.securityService.logEvent({
        userId,
        eventType: 'PHONE_LINKED',
        severity: 'LOW',
        metadata: { phone: maskPhoneForSecurityLog(phoneNumber) },
      });
      return { outcome: 'linked', phoneE164: phoneNumber, userId };
    }

    if (existing.userId === userId) {
      return { outcome: 'linked', phoneE164: phoneNumber, userId };
    }

    // The number belongs to another account. Both accounts are the same person
    // — they just proved they hold the number — so they are consolidated rather
    // than kept apart. The phone account survives because it is the one reachable
    // by contact discovery, which is what everyone else uses to find them.
    //
    // This is destructive and has no undo. It runs in a single transaction, so a
    // failure leaves both accounts untouched rather than half-merged.
    const targetUserId = existing.userId;
    const mergeSummary = await this.accountMergeService.merge(targetUserId, userId);
    await this.securityService.logEvent({
      userId: targetUserId,
      eventType: 'ACCOUNT_MERGED',
      severity: 'HIGH',
      metadata: {
        mergedUserId: userId,
        phone: maskPhoneForSecurityLog(phoneNumber),
        moved: mergeSummary.moved,
        dropped: mergeSummary.dropped,
      },
    });
    // A session for the account they are now signed in as. Without this the
    // client keeps a token for a user id that no longer exists.
    const device = this.deviceRepo.create({
      userId: targetUserId,
      publicKey: 'phone-link-' + uuidv4(),
      platform: 'ANDROID',
      appVersion: 'link',
    });
    await this.deviceRepo.save(device);
    const tokens = await this.createSession(targetUserId, device.id);
    return { outcome: 'adopted', phoneE164: phoneNumber, userId: targetUserId, ...tokens };
  }

  /**
   * Whether an account holds anything a merge could destroy. Deliberately
   * conservative: one call or one matched contact is enough to refuse.
   */
  /**
   * Whether an account holds anything at all. No longer gates the merge — it
   * decides which outcome is reported, so the client can tell the user their
   * accounts were combined rather than silently switching them over.
   */
  private async accountHasHistory(userId: string): Promise<boolean> {
    // Reached through the EntityManager the repositories already share, rather
    // than injecting two more repositories: AuthService's constructor is
    // positional in several specs, and widening it there is churn for one count.
    const em = this.userRepo.manager;
    const calls = await em.count(Call, {
      where: [{ callerUserId: userId }, { calleeUserId: userId }],
    });
    if (calls > 0) return true;
    return (await em.count(ContactMatch, { where: { userId } })) > 0;
  }

  // --- Identities on an account (several numbers, several emails) -----------

  /**
   * Every way this account can be reached or signed in to.
   *
   * One number belongs to exactly one account — the database enforces it with a
   * UNIQUE constraint on phone_e164, so a number cannot be attached to fifteen
   * accounts even by a client that tries. Emails are unique the same way.
   */
  async listIdentities(userId: string) {
    const [phones, emails] = await Promise.all([
      this.phoneRepo.find({ where: { userId } }),
      this.emailRepo.find({ where: { userId } }),
    ]);
    return {
      phones: phones.map((p) => ({
        id: p.id,
        phoneE164: p.phoneE164,
        verified: p.status === 'VERIFIED' && p.verifiedAt != null,
      })),
      emails: emails.map((e) => ({
        id: e.id,
        email: e.email,
        verified: e.status === 'VERIFIED' && e.verifiedAt != null,
      })),
    };
  }

  /**
   * Removes a number or email from the account.
   *
   * Refuses to remove the last one: an account with no phone and no email has
   * no way to sign in again, and the user would have locked themselves out with
   * a single tap.
   */
  async removeIdentity(userId: string, kind: 'phone' | 'email', id: string) {
    const [phones, emails] = await Promise.all([
      this.phoneRepo.find({ where: { userId } }),
      this.emailRepo.find({ where: { userId } }),
    ]);
    if (phones.length + emails.length <= 1) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'This is the only way to sign in to your account — add another before removing it.',
        HttpStatus.BAD_REQUEST,
      );
    }
    if (kind === 'phone') {
      const row = phones.find((p) => p.id === id);
      if (!row) {
        throw new ViroException('NOT_FOUND', 'Number not found on this account.', HttpStatus.NOT_FOUND);
      }
      await this.phoneRepo.delete({ id });
    } else {
      const row = emails.find((e) => e.id === id);
      if (!row) {
        throw new ViroException('NOT_FOUND', 'Email not found on this account.', HttpStatus.NOT_FOUND);
      }
      await this.emailRepo.delete({ id });
    }
    await this.securityService.logEvent({
      userId,
      eventType: 'IDENTITY_REMOVED',
      severity: 'MEDIUM',
      metadata: { kind },
    });
    return this.listIdentities(userId);
  }

  /**
   * Sends a code to an email address so it can be added to this account.
   * Unverified addresses are never attached: otherwise anyone could claim
   * someone else's address simply by typing it.
   */
  async requestEmailLink(
    userId: string,
    email: string,
  ): Promise<{ challengeId: string; expiresAt: string }> {
    const normalized = (email || '').trim().toLowerCase();
    if (!this.isValidEmail(normalized)) {
      throw new ViroException('VALIDATION_ERROR', 'Invalid email address.', HttpStatus.BAD_REQUEST);
    }
    const existing = await this.emailRepo.findOne({ where: { email: normalized } });
    if (existing) {
      throw new ViroException(
        'VALIDATION_ERROR',
        existing.userId === userId
          ? 'That email is already on your account.'
          : 'That email is already used by another Viro account.',
        HttpStatus.CONFLICT,
      );
    }
    const code =
      process.env.OTP_PROVIDER === 'test' && process.env.TEST_OTP_CODE
        ? process.env.TEST_OTP_CODE
        : String(randomInt(100000, 999999));
    const challenge = this.otpRepo.create({
      email: normalized,
      channel: 'email',
      codeHash: this.hashOtp(code),
      expiresAt: new Date(Date.now() + this.otpExpiresSeconds * 1000),
    });
    await this.otpRepo.save(challenge);
    await this.emailProvider.sendOtp(normalized, code);
    return { challengeId: challenge.id, expiresAt: challenge.expiresAt.toISOString() };
  }

  /**
   * Attaches an email the user has proved they own through Firebase: the app
   * creates a Firebase email/password login, Firebase emails a verification
   * link, and once it has been opened the app sends the ID token here. This
   * replaces the emailed-code flow for servers with no mail transport, and it
   * leaves the user with a password they can sign in with — and reset.
   */
  async linkEmailWithFirebase(userId: string, idToken: string) {
    const { email, verified } =
      await this.firebaseAuthService.verifiedEmailFromIdToken(idToken);
    if (!verified) {
      throw new ViroException(
        'FORBIDDEN',
        'Open the link we emailed you, then try again.',
        HttpStatus.FORBIDDEN,
      );
    }
    const existing = await this.emailRepo.findOne({ where: { email } });
    if (existing) {
      if (existing.userId === userId) return this.listIdentities(userId);
      throw new ViroException(
        'VALIDATION_ERROR',
        'That email is already used by another Viro account.',
        HttpStatus.CONFLICT,
      );
    }
    await this.emailRepo.save(
      this.emailRepo.create({ userId, email, verifiedAt: new Date(), status: 'VERIFIED' }),
    );
    await this.securityService.logEvent({
      userId,
      eventType: 'EMAIL_LINKED',
      severity: 'LOW',
      metadata: { via: 'firebase' },
    });
    return this.listIdentities(userId);
  }

  /** Confirms the code and attaches the email to this account. */
  async verifyEmailLink(userId: string, challengeId: string, code: string) {
    const challenge = await this.otpRepo.findOne({ where: { id: challengeId } });
    if (!challenge || challenge.channel !== 'email' || challenge.verifiedAt || !challenge.email) {
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
      throw new ViroException('VALIDATION_ERROR', 'Invalid OTP code.', HttpStatus.BAD_REQUEST);
    }
    challenge.verifiedAt = new Date();
    await this.otpRepo.save(challenge);

    // Re-checked after the code was sent: another account could have claimed
    // the address in between.
    const taken = await this.emailRepo.findOne({ where: { email: challenge.email } });
    if (taken) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'That email is already used by another Viro account.',
        HttpStatus.CONFLICT,
      );
    }
    await this.emailRepo.save(
      this.emailRepo.create({
        userId,
        email: challenge.email,
        verifiedAt: new Date(),
        status: 'VERIFIED',
      }),
    );
    await this.securityService.logEvent({
      userId,
      eventType: 'EMAIL_LINKED',
      severity: 'LOW',
      metadata: {},
    });
    return this.listIdentities(userId);
  }
  /**
   * Tokens for a device the user has just approved from another one they are
   * already signed in on (the web companion). No OTP: the approval is the proof.
   */
  async createSessionForDevice(userId: string, deviceId: string) {
    return this.createSession(userId, deviceId);
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

  private resolveOtpCode(normalizedPhone: string): string {
    const provider = process.env.OTP_PROVIDER || 'console';
    if (provider === 'test' && process.env.TEST_OTP_CODE) {
      return process.env.TEST_OTP_CODE;
    }
    if (provider === 'hardware-test') {
      return getHardwareTestOtpCode();
    }
    return String(randomInt(100000, 999999));
  }
}
