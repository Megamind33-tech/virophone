import { Controller, Post, Body, UseGuards, Req } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { AuthService } from './auth.service';
import { Get, Delete, Param, BadRequestException } from '@nestjs/common';
import { JwtAuthGuard } from './guards/jwt-auth.guard';
import { IsString, IsNotEmpty, Length, IsEmail, MaxLength } from 'class-validator';

class OtpRequestDto {
  @IsString()
  @IsNotEmpty()
  phoneE164!: string;
}

class EmailOtpRequestDto {
  @IsEmail()
  email!: string;
}

class EmailLinkRequestDto {
  @IsEmail()
  email!: string;
}

class EmailLinkVerifyDto {
  @IsString()
  @IsNotEmpty()
  challengeId!: string;

  @IsString()
  @Length(6, 6)
  code!: string;
}
class PhoneLinkRequestDto {
  @IsString()
  @IsNotEmpty()
  phoneE164!: string;
}

class PhoneLinkVerifyDto {
  @IsString()
  @IsNotEmpty()
  challengeId!: string;

  @IsString()
  @Length(6, 6)
  code!: string;
}
class FirebaseSignInDto {
  @IsString()
  @IsNotEmpty()
  @MaxLength(4096)
  idToken!: string;

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
  @Length(6, 32)
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
  @Throttle({ default: { limit: 5, ttl: 300000 } })
  async requestOtp(@Body() body: OtpRequestDto) {
    return this.authService.requestOtp(body.phoneE164);
  }

  @Post('otp/verify')
  @Throttle({ default: { limit: 10, ttl: 300000 } })
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


  /**
   * Sign in with a Firebase email/password account. The client authenticates
   * against Firebase, then posts the resulting ID token here; the server
   * verifies it and issues the ordinary Viro session pair.
   *
   * Rate-limited like OTP verify: an ID token is a bearer credential, and an
   * unthrottled exchange endpoint is a free oracle for testing stolen ones.
   */
  @Post('firebase/signin')
  @Throttle({ default: { limit: 10, ttl: 300000 } })
  async firebaseSignIn(@Body() body: FirebaseSignInDto) {
    return this.authService.signInWithFirebase(
      body.idToken,
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

  /**
   * Attaches a phone number to the signed-in account, so an account created by
   * email becomes reachable by phone-number contact discovery — people who only
   * know the number can then find and call it.
   *
   * Requires a live session: this links to whoever is signed in, and the OTP
   * proves they hold the number.
   */
  @Post('link/phone/request')
  @UseGuards(JwtAuthGuard)
  @Throttle({ default: { limit: 5, ttl: 300000 } })
  async requestPhoneLink(
    @Req() req: { user: { sub: string } },
    @Body() body: PhoneLinkRequestDto,
  ) {
    return this.authService.requestPhoneLink(req.user.sub, body.phoneE164);
  }

  /**
   * Confirms the OTP and connects the number.
   *
   * Returns outcome "linked" when the number was free, or "adopted" when it
   * already belonged to an account and this (empty) one was folded into it — in
   * which case the response carries a NEW session the client must switch to,
   * because the user id it was holding no longer exists.
   */
  @Post('link/phone/verify')
  @UseGuards(JwtAuthGuard)
  @Throttle({ default: { limit: 10, ttl: 300000 } })
  async verifyPhoneLink(
    @Req() req: { user: { sub: string } },
    @Body() body: PhoneLinkVerifyDto,
  ) {
    return this.authService.verifyPhoneLink(req.user.sub, body.challengeId, body.code);
  }

  /**
   * Every number and email on this account, with whether each is verified.
   * Drives the identities section of the profile page.
   */
  @Get('identities')
  @UseGuards(JwtAuthGuard)
  async listIdentities(@Req() req: { user: { sub: string } }) {
    return this.authService.listIdentities(req.user.sub);
  }

  /** Removes a number or email. Refuses to remove the last sign-in method. */
  @Delete('identities/:kind/:id')
  @UseGuards(JwtAuthGuard)
  async removeIdentity(
    @Req() req: { user: { sub: string } },
    @Param('kind') kind: string,
    @Param('id') id: string,
  ) {
    if (kind !== 'phone' && kind !== 'email') {
      throw new BadRequestException('kind must be phone or email');
    }
    return this.authService.removeIdentity(req.user.sub, kind, id);
  }

  /** Sends a code to an email address so it can be added to this account. */
  @Post('link/email/request')
  @UseGuards(JwtAuthGuard)
  @Throttle({ default: { limit: 5, ttl: 300000 } })
  async requestEmailLink(
    @Req() req: { user: { sub: string } },
    @Body() body: EmailLinkRequestDto,
  ) {
    return this.authService.requestEmailLink(req.user.sub, body.email);
  }

  @Post('link/email/verify')
  @UseGuards(JwtAuthGuard)
  @Throttle({ default: { limit: 10, ttl: 300000 } })
  async verifyEmailLink(
    @Req() req: { user: { sub: string } },
    @Body() body: EmailLinkVerifyDto,
  ) {
    return this.authService.verifyEmailLink(req.user.sub, body.challengeId, body.code);
  }
}
