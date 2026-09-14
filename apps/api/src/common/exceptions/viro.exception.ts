import { HttpException, HttpStatus } from '@nestjs/common';
import type { ApiErrorCode } from '@viro-reach/shared-types';

export class ViroException extends HttpException {
  constructor(
    code: ApiErrorCode,
    message: string,
    status: HttpStatus = HttpStatus.BAD_REQUEST,
  ) {
    super({ code, message }, status);
  }
}
