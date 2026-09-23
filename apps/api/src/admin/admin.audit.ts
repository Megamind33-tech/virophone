import { CallHandler, ExecutionContext, Injectable, Logger, NestInterceptor } from '@nestjs/common';
import { DataSource } from 'typeorm';
import { mergeMap } from 'rxjs/operators';

/** Record successful console mutations without retaining message contents or credentials. */
@Injectable()
export class AdminAuditInterceptor implements NestInterceptor {
  private readonly logger = new Logger(AdminAuditInterceptor.name);
  constructor(private readonly db: DataSource) {}
  intercept(context: ExecutionContext, next: CallHandler) {
    const req = context.switchToHttp().getRequest();
    if (!['POST', 'PATCH', 'DELETE'].includes(req.method)) return next.handle();
    return next.handle().pipe(mergeMap(async (result) => {
      await this.db.query(
        `INSERT INTO security_events (event_type, severity, metadata) VALUES ($1, 'INFO', $2)`,
        [`ADMIN_${req.method}`, JSON.stringify({
          by: req.adminActor, route: req.route.path, target: req.params,
          reason: typeof req.body?.reason === 'string' ? req.body.reason.trim().slice(0, 240) : undefined,
        })],
      ).catch(() => this.logger.error('Could not persist admin action audit event'));
      return result;
    }));
  }
}
