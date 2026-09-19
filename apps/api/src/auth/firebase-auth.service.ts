import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import type { App, ServiceAccount } from 'firebase-admin/app';
import type { DecodedIdToken } from 'firebase-admin/auth';

// Lazily required: firebase-admin v14 is ESM-only, and eagerly importing it
// breaks every Jest suite that touches AuthService.
/* eslint-disable @typescript-eslint/no-var-requires */
function adminApp() {
  return require('firebase-admin/app') as typeof import('firebase-admin/app');
}
function adminAuth() {
  return require('firebase-admin/auth') as typeof import('firebase-admin/auth');
}
import { v4 as uuidv4 } from 'uuid';
import { User } from '../database/entities/user.entity';
import { Profile } from '../database/entities/profile.entity';
import { EmailIdentity } from '../database/entities/email-identity.entity';
import { ViroException } from '../common/exceptions/viro.exception';
import { HttpStatus } from '@nestjs/common';

const APP_NAME = 'viro-auth';

/**
 * Turns a Firebase email/password sign-in into a Viro session.
 *
 * A Firebase UID is not a Viro account: the rest of the system authorises calls,
 * contacts and signaling against a Viro user id and a device id, so a client that
 * only held a Firebase ID token could sign in and then do nothing. This verifies
 * the ID token server-side and maps it onto a real Viro user, creating one on
 * first sight, so email sign-in produces exactly the same session shape that
 * phone-OTP already produces.
 *
 * Email accounts created this way have NO phone identity, which means they are
 * invisible to phone-number contact discovery. That is a deliberate limitation,
 * not an oversight — see docs and the note in AuthController.firebaseSignIn.
 */
@Injectable()
export class FirebaseAuthService {
  private readonly logger = new Logger('FirebaseAuthService');
  private app: App | null = null;

  constructor(
    @InjectRepository(User) private readonly userRepo: Repository<User>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    @InjectRepository(EmailIdentity)
    private readonly emailRepo: Repository<EmailIdentity>,
  ) {}

  isConfigured(): boolean {
    return !!(
      process.env.FIREBASE_SERVICE_ACCOUNT_JSON ||
      process.env.GOOGLE_APPLICATION_CREDENTIALS
    );
  }

  private getApp(): App {
    if (this.app) return this.app;
    const { initializeApp, getApps, cert, applicationDefault } = adminApp();
    const existing = getApps().find((a) => a?.name === APP_NAME);
    if (existing) {
      this.app = existing;
      return existing;
    }
    const inlineJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if (inlineJson) {
      this.app = initializeApp(
        {
          credential: cert(
            JSON.parse(inlineJson) as ServiceAccount,
          ),
        },
        APP_NAME,
      );
    } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
      this.app = initializeApp(
        { credential: applicationDefault() },
        APP_NAME,
      );
    } else {
      throw new ViroException(
        'INTERNAL_ERROR',
        'Firebase sign-in is not configured on this server.',
        HttpStatus.SERVICE_UNAVAILABLE,
      );
    }
    return this.app;
  }

  /**
   * Verifies a Firebase ID token and returns the Viro user it maps to,
   * creating the user on first sign-in. Returns the userId only — issuing
   * the device and session tokens stays in AuthService, so every sign-in
   * path produces sessions the same way.
   */
  async resolveUserFromIdToken(
    idToken: string,
  ): Promise<{ userId: string; email: string; isNewUser: boolean }> {
    let decoded: DecodedIdToken;
    try {
      // checkRevoked: a disabled or revoked Firebase account must not be able
      // to keep minting Viro sessions from an ID token it already holds.
      decoded = await adminAuth().getAuth(this.getApp()).verifyIdToken(idToken, true);
    } catch (e) {
      this.logger.warn(`FIREBASE_IDTOKEN_REJECTED ${(e as Error).message}`);
      throw new ViroException(
        'UNAUTHORIZED',
        'Invalid or expired Firebase sign-in.',
        HttpStatus.UNAUTHORIZED,
      );
    }

    const email = (decoded.email || '').trim().toLowerCase();
    if (!email) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'This Firebase account has no email address.',
        HttpStatus.BAD_REQUEST,
      );
    }
    if (decoded.email_verified !== true && requireVerifiedEmail()) {
      throw new ViroException(
        'FORBIDDEN',
        'Verify your email address before signing in.',
        HttpStatus.FORBIDDEN,
      );
    }

    const existing = await this.emailRepo.findOne({ where: { email } });
    if (existing) {
      if (!existing.verifiedAt && decoded.email_verified === true) {
        existing.verifiedAt = new Date();
        existing.status = 'VERIFIED';
        await this.emailRepo.save(existing);
      }
      return { userId: existing.userId, email, isNewUser: false };
    }

    const userId = uuidv4();
    await this.userRepo.save(this.userRepo.create({ id: userId, status: 'ACTIVE' }));
    await this.profileRepo.save(
      this.profileRepo.create({
        userId,
        displayName: decoded.name || email.split('@')[0] || '',
      }),
    );
    await this.emailRepo.save(
      this.emailRepo.create({
        userId,
        email,
        verifiedAt: decoded.email_verified === true ? new Date() : null,
        status: decoded.email_verified === true ? 'VERIFIED' : 'PENDING',
      }),
    );
    this.logger.log(`FIREBASE_SIGNIN_NEW_USER userId=${userId}`);
    return { userId, email, isNewUser: true };
  }
}

/**
 * Unverified-email sign-in is allowed by default because Firebase does not
 * verify at account creation and blocking it would make first sign-in
 * impossible without a verification flow in the app. Set
 * FIREBASE_REQUIRE_VERIFIED_EMAIL=true once that flow exists.
 */
function requireVerifiedEmail(): boolean {
  return (process.env.FIREBASE_REQUIRE_VERIFIED_EMAIL || 'false') === 'true';
}
