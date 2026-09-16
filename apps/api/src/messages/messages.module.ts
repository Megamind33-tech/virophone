import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { MessagesService } from './messages.service';
import { MessagesController } from './messages.controller';
import { Conversation } from '../database/entities/conversation.entity';
import { ConversationParticipant } from '../database/entities/conversation-participant.entity';
import { Message } from '../database/entities/message.entity';
import { MessageReceipt } from '../database/entities/message-receipt.entity';
import { BlocksModule } from '../blocks/blocks.module';
import { PushModule } from '../push/push.module';

@Module({
  imports: [
    TypeOrmModule.forFeature([
      Conversation,
      ConversationParticipant,
      Message,
      MessageReceipt,
    ]),
    BlocksModule,
    PushModule,
  ],
  controllers: [MessagesController],
  providers: [MessagesService],
  exports: [MessagesService],
})
export class MessagesModule {}
