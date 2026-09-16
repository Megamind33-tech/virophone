import { Controller, Post, Body, UseGuards, Req } from '@nestjs/common';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './guards/jwt-auth.guard';
import { IsString, IsNotEmpty, Length, IsEmail } from 'class-validator';

class OtpRequestDto {
  @IsString()
  @IsNotEmpty()
  phoneE164!: string;
}

class EmailOtpRequestDto {
  @IsEmail()
  email!: string;
}

class EmailOtpVerifyDto {
  @IsString()
  @IsNotEmpty()
  challengeId!: string;

  @IsString()
  @Length(6, 6)
  code!: string;

  @IsString()
  @IsNotEmpty()
  devicePublicKey!: string;

  @IsString()
  @IsNotEmpty()
  platform!: string;

  @IsString()
  @IsNotEmpty()
  appVersion!: string;
}

class OtpVerifyDto {
  @IsString()
  @IsNotEmpty()
  challengeId!: string;

  @IsString()
  @Length(6, 6)
  code!: string;

  @IsString()
  @IsNotEmpty()
  devicePublicKey!: string;

  @IsString()
  @IsNotEmpty()
  platform!: string;

  @IsString()
  @IsNotEmpty()
  appVersion!: string;
}

class RefreshDto {
  @IsString()
  @IsNotEmpty()
  refreshToken!: string;
}

@Controller('api/v1/auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Post('otp/request')
  async requestOtp(@Body() body: OtpRequestDto) {
    return this.authService.requestOtp(body.phoneE164);
  }

  @Post('otp/verify')
  async verifyOtp(@Body() body: OtpVerifyDto) {
    return this.authService.verifyOtp(
      body.challengeId,
      body.code,
      body.devicePublicKey,
      body.platform,
      body.appVersion,
    );
  }

  @Post('email/otp/request')
  async requestEmailOtp(@Body() body: EmailOtpRequestDto) {
    return this.authService.requestEmailOtp(body.email);
  }

  @Post('email/otp/verify')
  async verifyEmailOtp(@Body() body: EmailOtpVerifyDto) {
    return this.authService.verifyEmailOtp(
      body.challengeId,
      body.code,
      body.devicePublicKey,
      body.platform,
      body.appVersion,
    );
  }

  @Post('refresh')
  async refresh(@Body() body: RefreshDto) {
    return this.authService.refreshToken(body.refreshToken);
  }

  @Post('logout')
  @UseGuards(JwtAuthGuard)
  async logout(@Req() req: { user: { sub: string; deviceId: string } }) {
    await this.authService.logout(req.user.sub, req.user.deviceId);
    return { success: true };
  }
}
