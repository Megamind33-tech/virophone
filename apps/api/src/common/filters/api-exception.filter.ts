import {
  ExceptionFilter,
  Catch,
  ArgumentsHost,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import { Request, Response } from 'express';
import type { ApiError, ApiErrorCode } from '@viro-reach/shared-types';

@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(ApiExceptionFilter.name);

  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<Response>();
    const request = ctx.getRequest<Request>();
    const requestId = (request.headers['x-request-id'] as string) || 'unknown';

    let status = HttpStatus.INTERNAL_SERVER_ERROR;
    let code: ApiErrorCode = 'INTERNAL_ERROR';
    let message = 'An unexpected error occurred.';

    if (exception instanceof HttpException) {
      status = exception.getStatus();
      const body = exception.getResponse();
      if (typeof body === 'object' && body !== null) {
        const obj = body as Record<string, unknown>;
        code = (obj.code as ApiErrorCode) || code;
        message = (obj.message as string) || message;
      } else {
        message = String(body);
      }
    }

    // The caller only ever sees "unexpected error"; without this line the
    // cause leaves no trace anywhere and a production 500 is undiagnosable.
    if (status >= 500) {
      this.logger.error(
        `${request.method} ${request.originalUrl} -> ${status} reqId=${requestId}`,
        exception instanceof Error ? exception.stack : String(exception),
      );
    }

    const errorBody: ApiError = { code, message, requestId };
    response.status(status).json(errorBody);
  }
}
