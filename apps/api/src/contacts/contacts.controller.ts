import { Controller, Post, Body, UseGuards, Req } from '@nestjs/common';
import { ContactsService } from './contacts.service';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { IsArray, IsString, ArrayMaxSize, IsOptional } from 'class-validator';

class DiscoverDto {
  @IsArray()
  @IsString({ each: true })
  @ArrayMaxSize(200)
  phonesE164!: string[];

  @IsString()
  @IsOptional()
  defaultRegion?: string;
}

@Controller('api/v1/contacts')
export class ContactsController {
  constructor(private readonly contactsService: ContactsService) {}

  @Post('discover')
  @UseGuards(JwtAuthGuard)
  async discover(@Req() req: { user: { sub: string } }, @Body() body: DiscoverDto) {
    return this.contactsService.discover(
      req.user.sub,
      body.phonesE164,
      body.defaultRegion || 'ZM',
    );
  }
}
