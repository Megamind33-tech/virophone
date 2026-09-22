import { Module } from '@nestjs/common';
import { CampaignsController } from './campaigns.controller';
import { AuthModule } from '../auth/auth.module';

/** The one campaign surface the app can see: what is running, right now. */
@Module({ imports: [AuthModule], controllers: [CampaignsController] })
export class CampaignsModule {}
