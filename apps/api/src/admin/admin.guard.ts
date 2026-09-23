import {
  Injectable,
  CanActivate,
  ExecutionContext,
  UnauthorizedException,
} from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User } from '../database/entities/user.entity';
import { Device } from '../database/entities/device.entity';

/**
 * Allows either a matching X-Admin-Key header or a Bearer JWT whose user
 * has ADMIN / SECURITY_ADMIN role.
 */
@Injectable()
export class AdminGuard implements CanActivate {
  constructor(
    @InjectRepository(User) private readonly users: Repository<User>,
    private readonly jwt: JwtService,
    @InjectRepository(Device) private readonly devices: Repository<Device>,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest<{
      headers: Record<string, string | string[] | undefined>;
      adminActor?: string;
    }>();
    const rawKey = req.headers['x-admin-key'];
    const key = Array.isArray(rawKey) ? rawKey[0] : rawKey;
    const expected = process.env.ADMIN_API_KEY;
    if (expected && key && key === expected) {
      req.adminActor = 'admin-key';
      return true;
    }

    const auth = req.headers.authorization;
    const header = Array.isArray(auth) ? auth[0] : auth;
    const token = header?.startsWith('Bearer ') ? header.slice(7) : null;
    if (token) {
      try {
        const payload = this.jwt.verify<{ sub: string; deviceId: string }>(token, {
          secret: process.env.JWT_ACCESS_SECRET || 'dev_access_secret',
        });
        const user = await this.users.findOne({ where: { id: payload.sub } });
        const device = payload.deviceId ? await this.devices.findOne({ where: { id: payload.deviceId, userId: payload.sub } }) : null;
        if (user?.status === 'ACTIVE' && device && !device.revokedAt &&
            (user.adminRole === 'ADMIN' || user.adminRole === 'SECURITY_ADMIN')) {
          req.adminActor = user.id;
          return true;
        }
      } catch {
        /* fall through to unauthorized */
      }
    }

    throw new UnauthorizedException({
      code: 'UNAUTHORIZED',
      message: 'Admin credentials required.',
    });
  }
}
