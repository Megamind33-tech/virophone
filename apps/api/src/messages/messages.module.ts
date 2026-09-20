import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { MessagesService } from './messages.service';
import { MessagesController } from './messages.controller';
import { MessagesSweeper } from './messages.sweeper';
import { MediaStore } from './media.store';
import { Conversation } from '../database/entities/conversation.entity';
import { ConversationParticipant } from '../database/entities/conversation-participant.entity';
import { Message } from '../database/entities/message.entity';
import { MessageMention } from '../database/entities/message-mention.entity';
import { MessageReceipt } from '../database/entities/message-receipt.entity';
import {
  ConversationPin,
  MediaObject,
  MessageHidden,
  MessageReaction,
  MessageStar,
  MessageView,
  PollVote,
} from '../database/entities/messaging-extras.entity';
import { LinkPreviewService } from './link-preview.service';
import { GifService } from './gif.service';
import { TranscriptionService } from './transcription.service';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';
import { BlocksModule } from '../blocks/blocks.module';
import { PushModule } from '../push/push.module';

@Module({
  imports: [
    TypeOrmModule.forFeature([
      Conversation,
      ConversationParticipant,
      Message,
      MessageReceipt,
      MessageReaction,
      MessageHidden,
      MessageView,
      MessageStar,
      ConversationPin,
      MediaObject,
      ViroConnection,
      Profile,
      PollVote,
      MessageMention,
    ]),
    BlocksModule,
    PushModule,
  ],
  controllers: [MessagesController],
  providers: [MessagesService, MessagesSweeper, MediaStore, LinkPreviewService, GifService, TranscriptionService],
  exports: [MessagesService],
})
export class MessagesModule {}
