import { HttpStatus, Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { LessThan, Repository } from 'typeorm';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'crypto';
import { DeviceLinkRequest } from '../database/entities/device-link-request.entity';
import { Device } from '../database/entities/device.entity';
import { Profile } from '../database/entities/profile.entity';
import { AuthService } from '../auth/auth.service';
import { ViroException } from '../common/exceptions/viro.exception';

/** No 0/O or 1/I: this is read off one screen and typed on another. */
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const CODE_LENGTH = 8;
const LINK_TTL_MS = 3 * 60 * 1000;
const MAX_ATTEMPTS_PER_MINUTE = 10;

const sha256 = (value: string) => createHash('sha256').update(value).digest('hex');

/**
 * Linking another device — today the web companion.
 *
 * The browser asks for a link and shows a short code. The phone, already
 * signed in, types that code to approve it. Tokens are minted only then, and
 * handed over exactly once to whoever proves with the secret that they started
 * the request. A request lasts three minutes.
 */
@Injectable()
export class DeviceLinkService {
  private readonly logger = new Logger(DeviceLinkService.name);
  /** Wrong codes, per person: guessing is pointless, but it shouldn't be free either. */
  private readonly attempts = new Map<string, number[]>();

  constructor(
    @InjectRepository(DeviceLinkRequest) private readonly linkRepo: Repository<DeviceLinkRequest>,
    @InjectRepository(Device) private readonly deviceRepo: Repository<Device>,
    @InjectRepository(Profile) private readonly profileRepo: Repository<Profile>,
    private readonly authService: AuthService,
  ) {}

  /** The browser starts here. Nobody is signed in yet. */
  async start(label?: string) {
    await this.sweep();
    const secret = randomBytes(24).toString('base64url');
    const request = this.linkRepo.create({
      id: randomUUID(),
      code: await this.freshCode(),
      secretHash: sha256(secret),
      platform: 'WEB',
      label: (label || '').replace(/[^\x20-\x7e]/g, '').slice(0, 80) || 'Web browser',
      expiresAt: new Date(Date.now() + LINK_TTL_MS),
    });
    await this.linkRepo.save(request);
    return {
      linkId: request.id,
      code: request.code,
      secret,
      expiresAt: request.expiresAt.toISOString(),
    };
  }

  /**
   * The browser waits here. Once approved it gets the tokens, once — after
   * that the row keeps nothing worth stealing.
   */
  async poll(linkId: string, secret: string) {
    const request = await this.linkRepo.findOne({ where: { id: linkId } });
    if (!request || !this.secretMatches(request, secret)) {
      throw new ViroException('NOT_FOUND', 'That link request is not valid.', HttpStatus.NOT_FOUND);
    }
    if (request.expiresAt.getTime() <= Date.now() && !request.approvedAt) {
      return { status: 'EXPIRED' as const };
    }
    if (!request.approvedAt) return { status: 'PENDING' as const };
    if (!request.tokens) return { status: 'CLAIMED' as const };

    const tokens = request.tokens;
    request.tokens = null;
    request.claimedAt = new Date();
    await this.linkRepo.save(request);
    const profile = request.userId ? await this.profileRepo.findOne({ where: { userId: request.userId } }) : null;
    this.logger.log(`DEVICE_LINK_CLAIMED device=${request.deviceId}`);
    return {
      status: 'APPROVED' as const,
      userId: request.userId,
      displayName: profile?.displayName ?? null,
      ...tokens,
    };
  }

  /** The phone approves, by typing the code the browser is showing. */
  async approve(userId: string, rawCode: string) {
    this.throttle(userId);
    const code = (rawCode || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    const request = code ? await this.linkRepo.findOne({ where: { code } }) : null;
    if (!request || request.expiresAt.getTime() <= Date.now()) {
      this.recordAttempt(userId);
      throw new ViroException('NOT_FOUND', 'That code is wrong or has expired. Ask for a new one.', HttpStatus.NOT_FOUND);
    }
    if (request.approvedAt) {
      throw new ViroException('VALIDATION_ERROR', 'That code has already been used.', HttpStatus.BAD_REQUEST);
    }

    const device = await this.deviceRepo.save(
      this.deviceRepo.create({
        userId,
        // A browser holds no device key; the session's own tokens are the credential.
        publicKey: `web:${randomBytes(16).toString('hex')}`,
        platform: 'WEB',
        appVersion: request.label?.slice(0, 20) || 'web',
      }),
    );
    const tokens = await this.authService.createSessionForDevice(userId, device.id);
    request.userId = userId;
    request.deviceId = device.id;
    request.approvedAt = new Date();
    request.tokens = tokens;
    await this.linkRepo.save(request);
    this.logger.log(`DEVICE_LINK_APPROVED user=${userId} device=${device.id}`);
    return { linked: true, deviceId: device.id, label: request.label };
  }

  /** Old requests are of no use to anyone; don't keep them around. */
  private async sweep() {
    await this.linkRepo.delete({ expiresAt: LessThan(new Date(Date.now() - LINK_TTL_MS)) });
  }

  private async freshCode(): Promise<string> {
    for (let attempt = 0; attempt < 10; attempt++) {
      const bytes = randomBytes(CODE_LENGTH);
      let code = '';
      for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
      const clash = await this.linkRepo.findOne({ where: { code } });
      if (!clash) return code;
    }
    throw new ViroException('INTERNAL_ERROR', 'Could not start a link. Try again.', HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private secretMatches(request: DeviceLinkRequest, secret: string): boolean {
    const given = Buffer.from(sha256(secret || ''));
    const known = Buffer.from(request.secretHash);
    return given.length === known.length && timingSafeEqual(given, known);
  }

  private throttle(userId: string) {
    const now = Date.now();
    const recent = (this.attempts.get(userId) ?? []).filter((t) => now - t < 60_000);
    if (recent.length >= MAX_ATTEMPTS_PER_MINUTE) {
      throw new ViroException('RATE_LIMITED', 'Too many tries. Wait a minute.', HttpStatus.TOO_MANY_REQUESTS);
    }
    this.attempts.set(userId, recent);
  }

  private recordAttempt(userId: string) {
    const now = Date.now();
    const recent = (this.attempts.get(userId) ?? []).filter((t) => now - t < 60_000);
    recent.push(now);
    this.attempts.set(userId, recent);
  }
}
