import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { UsersService } from './users.service';
import { VisibilityService } from './visibility.service';
import { UsersController } from './users.controller';
import { ContactMatch } from '../database/entities/contact-match.entity';
import { EmailIdentity } from '../database/entities/email-identity.entity';
import { Profile } from '../database/entities/profile.entity';
import { PhoneIdentity } from '../database/entities/phone-identity.entity';
import { User } from '../database/entities/user.entity';
import { Device } from '../database/entities/device.entity';
import { Session } from '../database/entities/session.entity';
import { Call } from '../database/entities/call.entity';
import { Block } from '../database/entities/block.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { PushToken } from '../database/entities/push-token.entity';
import { ConversationParticipant } from '../database/entities/conversation-participant.entity';
import { Message } from '../database/entities/message.entity';

@Module({
  imports: [
    TypeOrmModule.forFeature([
      Profile,
      EmailIdentity,
      ContactMatch,
      PhoneIdentity,
      User,
      Device,
      Session,
      Call,
      Block,
      ViroConnection,
      PushToken,
      ConversationParticipant,
      Message,
    ]),
  ],
  controllers: [UsersController],
  providers: [UsersService, VisibilityService],
  exports: [UsersService, VisibilityService],
})
export class UsersModule {}
