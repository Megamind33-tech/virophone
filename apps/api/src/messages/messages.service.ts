import { Injectable, HttpStatus } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, IsNull, MoreThan, Not, Repository } from 'typeorm';
import { Conversation } from '../database/entities/conversation.entity';
import { ConversationParticipant } from '../database/entities/conversation-participant.entity';
import { Message } from '../database/entities/message.entity';
import { MessageReceipt } from '../database/entities/message-receipt.entity';
import { BlocksService } from '../blocks/blocks.service';
import { PushService } from '../push/push.service';
import { RealtimeRegistry } from '../realtime/realtime.registry';
import { ViroException } from '../common/exceptions/viro.exception';

export interface SendMessageInput {
  toUserId?: string;
  conversationId?: string;
  body: string;
  clientMsgId?: string;
}

export interface MessageDto {
  id: string;
  conversationId: string;
  senderUserId: string;
  body: string | null;
  type: string;
  clientMsgId: string | null;
  createdAt: string;
}

@Injectable()
export class MessagesService {
  constructor(
    @InjectRepository(Conversation)
    private readonly convRepo: Repository<Conversation>,
    @InjectRepository(ConversationParticipant)
    private readonly partRepo: Repository<ConversationParticipant>,
    @InjectRepository(Message)
    private readonly msgRepo: Repository<Message>,
    @InjectRepository(MessageReceipt)
    private readonly receiptRepo: Repository<MessageReceipt>,
    private readonly blocksService: BlocksService,
    private readonly pushService: PushService,
    private readonly realtime: RealtimeRegistry,
  ) {}

  private toDto(m: Message): MessageDto {
    return {
      id: m.id,
      conversationId: m.conversationId,
      senderUserId: m.senderUserId,
      body: m.body,
      type: m.type,
      clientMsgId: m.clientMsgId,
      createdAt: (m.createdAt instanceof Date
        ? m.createdAt
        : new Date(m.createdAt)
      ).toISOString(),
    };
  }

  /** Finds or creates the canonical 1:1 conversation for two users. */
  async getOrCreateDm(userA: string, userB: string): Promise<Conversation> {
    const [a, b] = [userA, userB].sort();
    const dmKey = `${a}:${b}`;
    const existing = await this.convRepo.findOne({ where: { dmKey } });
    if (existing) return existing;
    try {
      const conv = await this.convRepo.save(
        this.convRepo.create({ isGroup: false, dmKey, createdBy: userA }),
      );
      await this.partRepo.save([
        this.partRepo.create({ conversationId: conv.id, userId: a }),
        this.partRepo.create({ conversationId: conv.id, userId: b }),
      ]);
      return conv;
    } catch {
      // Concurrent create — the unique dm_key lost the race; fetch the winner.
      const conv = await this.convRepo.findOne({ where: { dmKey } });
      if (conv) return conv;
      throw new ViroException(
        'INTERNAL_ERROR',
        'Could not open conversation.',
        HttpStatus.CONFLICT,
      );
    }
  }

  private async participantIds(conversationId: string): Promise<string[]> {
    const rows = await this.partRepo.find({ where: { conversationId } });
    return rows.map((r) => r.userId);
  }

  private async assertMember(conversationId: string, userId: string) {
    const part = await this.partRepo.findOne({
      where: { conversationId, userId },
    });
    if (!part) {
      throw new ViroException(
        'FORBIDDEN',
        'Not a participant in this conversation.',
        HttpStatus.FORBIDDEN,
      );
    }
    return part;
  }

  async sendMessage(
    senderId: string,
    senderDeviceId: string | null,
    input: SendMessageInput,
  ) {
    const body = (input.body || '').trim();
    if (!body) {
      throw new ViroException(
        'VALIDATION_ERROR',
        'Message body is required.',
        HttpStatus.BAD_REQUEST,
      );
    }

    let conversation: Conversation;
    let recipientIds: string[];

    if (input.conversationId) {
      const conv = await this.convRepo.findOne({
        where: { id: input.conversationId },
      });
      if (!conv) {
        throw new ViroException('NOT_FOUND', 'Conversation not found.', HttpStatus.NOT_FOUND);
      }
      await this.assertMember(conv.id, senderId);
      conversation = conv;
      recipientIds = (await this.participantIds(conv.id)).filter(
        (id) => id !== senderId,
      );
    } else if (input.toUserId) {
      if (input.toUserId === senderId) {
        throw new ViroException(
          'VALIDATION_ERROR',
          'Cannot message yourself.',
          HttpStatus.BAD_REQUEST,
        );
      }
      if (await this.blocksService.isBlocked(senderId, input.toUserId)) {
        throw new ViroException(
          'FORBIDDEN',
          'This person is unavailable.',
          HttpStatus.FORBIDDEN,
        );
      }
      conversation = await this.getOrCreateDm(senderId, input.toUserId);
      recipientIds = [input.toUserId];
    } else {
      throw new ViroException(
        'VALIDATION_ERROR',
        'toUserId or conversationId is required.',
        HttpStatus.BAD_REQUEST,
      );
    }

    // Idempotency on client-supplied id (safe retries / offline outbox flush).
    if (input.clientMsgId) {
      const dup = await this.msgRepo.findOne({
        where: {
          conversationId: conversation.id,
          senderUserId: senderId,
          clientMsgId: input.clientMsgId,
        },
      });
      if (dup) {
        return { conversationId: conversation.id, message: this.toDto(dup) };
      }
    }

    const message = await this.msgRepo.save(
      this.msgRepo.create({
        conversationId: conversation.id,
        senderUserId: senderId,
        senderDeviceId: senderDeviceId ?? null,
        clientMsgId: input.clientMsgId ?? null,
        type: 'TEXT',
        body,
      }),
    );

    if (recipientIds.length > 0) {
      await this.receiptRepo.save(
        recipientIds.map((uid) =>
          this.receiptRepo.create({ messageId: message.id, userId: uid }),
        ),
      );
    }
    await this.convRepo.update({ id: conversation.id }, { updatedAt: new Date() });

    // Deliver in realtime; fall back to push when the recipient is offline.
    const frame = {
      type: 'message.new',
      conversationId: conversation.id,
      message: this.toDto(message),
    };
    for (const uid of recipientIds) {
      const delivered = await this.realtime.deliverToUser(uid, frame);
      if (delivered > 0) {
        await this.receiptRepo.update(
          { messageId: message.id, userId: uid },
          { deliveredAt: new Date() },
        );
      } else {
        await this.pushService.sendToUser(uid, {
          title: 'New message',
          body: body.slice(0, 120),
          data: {
            type: 'message',
            conversationId: conversation.id,
            messageId: message.id,
            senderUserId: senderId,
          },
        });
      }
    }

    return { conversationId: conversation.id, message: this.toDto(message) };
  }

  async listConversations(userId: string) {
    const parts = await this.partRepo.find({ where: { userId } });
    if (parts.length === 0) return [];
    const convIds = parts.map((p) => p.conversationId);
    const convs = await this.convRepo.find({
      where: { id: In(convIds) },
      order: { updatedAt: 'DESC' },
    });

    const result = [];
    for (const conv of convs) {
      const part = parts.find((p) => p.conversationId === conv.id)!;
      const lastMessage = await this.msgRepo.findOne({
        where: { conversationId: conv.id },
        order: { createdAt: 'DESC' },
      });
      const unread = await this.msgRepo.count({
        where: {
          conversationId: conv.id,
          senderUserId: Not(userId),
          createdAt: MoreThan(part.lastReadAt ?? new Date(0)),
        },
      });
      const participants = await this.participantIds(conv.id);
      result.push({
        id: conv.id,
        isGroup: conv.isGroup,
        title: conv.title,
        participants,
        lastMessage: lastMessage ? this.toDto(lastMessage) : null,
        unread,
        updatedAt: conv.updatedAt,
      });
    }
    return result;
  }

  async history(userId: string, conversationId: string, limit = 50) {
    await this.assertMember(conversationId, userId);
    const msgs = await this.msgRepo.find({
      where: { conversationId },
      order: { createdAt: 'DESC' },
      take: Math.min(Math.max(limit, 1), 200),
    });
    return msgs.reverse().map((m) => this.toDto(m));
  }

  async markRead(userId: string, conversationId: string) {
    await this.assertMember(conversationId, userId);
    const now = new Date();
    await this.partRepo.update({ conversationId, userId }, { lastReadAt: now });
    const msgIds = (
      await this.msgRepo.find({ where: { conversationId }, select: ['id'] })
    ).map((m) => m.id);
    if (msgIds.length > 0) {
      await this.receiptRepo.update(
        { userId, messageId: In(msgIds), deliveredAt: IsNull() },
        { deliveredAt: now },
      );
      await this.receiptRepo.update(
        { userId, messageId: In(msgIds), readAt: IsNull() },
        { readAt: now },
      );
    }
    return { ok: true };
  }
}
