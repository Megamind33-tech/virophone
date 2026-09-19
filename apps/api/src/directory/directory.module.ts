import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { DirectoryService } from './directory.service';
import { DirectoryController, InviteController } from './directory.controller';
import { Profile } from '../database/entities/profile.entity';
import { Block } from '../database/entities/block.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { EmailIdentity } from '../database/entities/email-identity.entity';
import { ContactMatch } from '../database/entities/contact-match.entity';

@Module({
  imports: [TypeOrmModule.forFeature([Profile, Block, ViroConnection, EmailIdentity, ContactMatch])],
  controllers: [DirectoryController, InviteController],
  providers: [DirectoryService],
  exports: [DirectoryService],
})
export class DirectoryModule {}
