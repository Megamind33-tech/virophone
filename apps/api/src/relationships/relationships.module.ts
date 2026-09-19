import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import {
  Commitment,
  ImportantDate,
  Loop,
  LoopAnswer,
  Relationship,
  RelationshipCheckin,
  RelationshipSettings,
  UserAchievement,
} from '../database/entities/relationship.entity';
import { MediaObject } from '../database/entities/messaging-extras.entity';
import { Profile } from '../database/entities/profile.entity';
import { MessagesModule } from '../messages/messages.module';
import { PushModule } from '../push/push.module';
import { RelationshipsService } from './relationships.service';
import { LoopsService } from './loops.service';
import { LoopsController, RelationshipsController } from './relationships.controller';

@Module({
  imports: [
    TypeOrmModule.forFeature([
      Relationship,
      ImportantDate,
      Commitment,
      Loop,
      LoopAnswer,
      UserAchievement,
      RelationshipSettings,
      RelationshipCheckin,
      MediaObject,
      Profile,
    ]),
    MessagesModule,
    PushModule,
  ],
  controllers: [RelationshipsController, LoopsController],
  providers: [RelationshipsService, LoopsService],
})
export class RelationshipsModule {}
