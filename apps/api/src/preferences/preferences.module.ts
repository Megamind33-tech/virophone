import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { PreferencesController } from './preferences.controller';
import { PreferencesService } from './preferences.service';
import { ContactPreference } from '../database/entities/contact-preference.entity';
import { UserAppPreference } from '../database/entities/user-app-preference.entity';

@Module({
  imports: [TypeOrmModule.forFeature([ContactPreference, UserAppPreference])],
  controllers: [PreferencesController],
  providers: [PreferencesService],
  exports: [PreferencesService],
})
export class PreferencesModule {}
