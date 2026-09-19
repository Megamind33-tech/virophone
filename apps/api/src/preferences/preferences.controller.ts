import { Controller, Get, Put, Body, UseGuards, Req } from '@nestjs/common';
import {
  IsArray, IsBoolean, IsOptional, IsString, MaxLength, ValidateNested, ArrayMaxSize,
} from 'class-validator';
import { Type } from 'class-transformer';
import { PreferencesService } from './preferences.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';

class ContactPreferenceDto {
  @IsString()
  phoneE164!: string;

  @IsBoolean()
  @IsOptional()
  isFavorite?: boolean;

  @IsString()
  @IsOptional()
  @MaxLength(120)
  customDisplayName?: string | null;

  @IsBoolean()
  @IsOptional()
  isHidden?: boolean;

  /** Blocking a contact who has no Viro account — see migration 009. */
  @IsBoolean()
  @IsOptional()
  isBlocked?: boolean;

  @IsBoolean()
  @IsOptional()
  isSpam?: boolean;
}

class ContactPreferencesDto {
  @IsArray()
  @ArrayMaxSize(2000)
  @ValidateNested({ each: true })
  @Type(() => ContactPreferenceDto)
  contacts!: ContactPreferenceDto[];
}

class AppearancePreferenceDto {
  @IsString()
  @IsOptional()
  themeMode?: string;

  @IsString()
  @IsOptional()
  fontSize?: string;

  @IsString()
  @IsOptional()
  density?: string;
}

@Controller('api/v1/preferences')
@UseGuards(JwtAuthGuard)
export class PreferencesController {
  constructor(private readonly preferences: PreferencesService) {}

  /** Everything the client needs to restore itself on a new device. */
  @Get()
  async getAll(@Req() req: { user: { sub: string } }) {
    return this.preferences.getAll(req.user.sub);
  }

  @Put('contacts')
  async putContacts(
    @Req() req: { user: { sub: string } },
    @Body() body: ContactPreferencesDto,
  ) {
    return this.preferences.upsertContacts(req.user.sub, body.contacts);
  }

  @Put('appearance')
  async putAppearance(
    @Req() req: { user: { sub: string } },
    @Body() body: AppearancePreferenceDto,
  ) {
    return this.preferences.upsertAppearance(req.user.sub, body);
  }
}
