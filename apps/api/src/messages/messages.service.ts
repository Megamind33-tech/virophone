import { Injectable, HttpStatus, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
import { Conversation } from '../database/entities/conversation.entity';
import { ConversationParticipant } from '../database/entities/conversation-participant.entity';
import { Message } from '../database/entities/message.entity';
import { MessageReceipt } from '../database/entities/message-receipt.entity';
import {
  ConversationPin,
  MediaObject,
  MessageHidden,
  MessageReaction,
  MessageStar,
  MessageView,
} from '../database/entities/messaging-extras.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';
import { BlocksService } from '../blocks/blocks.service';
import { PushService } from '../push/push.service';
import { RealtimeRegistry } from '../realtime/realtime.registry';
import { ViroException } from '../common/exceptions/viro.exception';
import { MediaStore } from './media.store';

/** Editing is allowed for this long after sending, as in WhatsApp. */
export const EDIT_WINDOW_MS = 15 * 60 * 1000;
/** Delete-for-everyone is allowed for this long after sending. */
export const DELETE_FOR_EVERYONE_WINDOW_MS = 48 * 60 * 60 * 1000;
/** Private sessions: between 5 minutes and 30 days. */
export const PRIVATE_MIN_SECONDS = 5 * 60;
export const PRIVATE_MAX_SECONDS = 30 * 24 * 60 * 60;
/** Allowed disappearing timers: 24h, 7d, 90d (and off). */
export const DISAPPEARING_CHOICES = [24 * 3600, 7 * 24 * 3600, 90 * 24 * 3600];

export interface SendMessageInput {
  toUserId?: string;
  conversationId?: string;
  body?: string;
  clientMsgId?: string;
  type?: string;
  replyToId?: string;
  mediaId?: string;
  viewOnce?: boolean;
  forwarded?: boolean;
  deliverAt?: string;
  effect?: string;
}

export interface MediaDto {
  id: string;
  kind: string;
  mime: string;
  sizeBytes: number;
  durationMs: number | null;
  waveform: string | null;
  width: number | null;
  height: number | null;
}

export interface ReplyPreviewDto {
  id: string;
  senderUserId: string;
  type: string;
  body: string | null;
  deleted: boolean;
}

export interface MessageDto {
  id: string;
  conversationId: string;
  senderUserId: string;
  body: string | null;
  type: string;
  clientMsgId: string | null;
  createdAt: string;
  updatedAt: string;
  editedAt: string | null;
  deletedAt: string | null;
  expiresAt: string | null;
  deliverAt: string | null;
  replyTo: ReplyPreviewDto | null;
  reactions: { userId: string; emoji: string }[];
  media: MediaDto | null;
  viewOnce: boolean;
  /** For view-once: whether the recipient has opened it (sender's view) or I have (recipient's view). */
  viewed: boolean;
  forwarded: boolean;
  starred: boolean;
  metadata: Record<string, unknown> | null;
}

export interface ConversationSummaryDto {
  id: string;
  kind: string;
  isGroup: boolean;
  title: string | null;
  participants: string[];
  createdBy: string | null;
  lastMessage: MessageDto | null;
  unread: number;
  updatedAt: string;
  hidden: boolean;
  mutedUntil: string | null;
  clearedAt: string | null;
  resetAt: string | null;
  disappearingSeconds: number | null;
  expiresAt: string | null;
  /** Read/delivery watermarks of the OTHER participants, for ticks. */
  peerLastReadAt: string | null;
  peerLastDeliveredAt: string | null;
  pinnedMessageIds: string[];
}

/**
 * TypeORM's raw query() returns UPDATE/DELETE ... RETURNING as
 * [rows, affectedCount] but SELECT as plain rows. Treating the pair as rows
 * made the sweeper act on undefined ids — and findOne({ id: undefined })
 * silently matches the FIRST message in the table.
 */
function returningRows<T>(result: unknown): T[] {
  if (Array.isArray(result) && result.length === 2 && Array.isArray(result[0]) && typeof result[1] === 'number') {
    return result[0] as T[];
  }
  return Array.isArray(result) ? (result as T[]) : [];
}

const iso = (d: Date | string | null | undefined): string | null =>
  d ? (d instanceof Date ? d : new Date(d)).toISOString() : null;

@Injectable()
export class MessagesService {
  private readonly logger = new Logger('Messages');

  constructor(
    @InjectRepository(Conversation)
    private readonly convRepo: Repository<Conversation>,
    @InjectRepository(ConversationParticipant)
    private readonly partRepo: Repository<ConversationParticipant>,
    @InjectRepository(Message)
    private readonly msgRepo: Repository<Message>,
    @InjectRepository(MessageReceipt)
    private readonly receiptRepo: Repository<MessageReceipt>,
    @InjectRepository(MessageReaction)
    private readonly reactionRepo: Repository<MessageReaction>,
    @InjectRepository(MessageHidden)
    private readonly hiddenRepo: Repository<MessageHidden>,
    @InjectRepository(MessageView)
    private readonly viewRepo: Repository<MessageView>,
    @InjectRepository(MessageStar)
    private readonly starRepo: Repository<MessageStar>,
    @InjectRepository(ConversationPin)
    private readonly pinRepo: Repository<ConversationPin>,
    @InjectRepository(MediaObject)
    private readonly mediaRepo: Repository<MediaObject>,
    @InjectRepository(ViroConnection)
    private readonly connectionRepo: Repository<ViroConnection>,
    @InjectRepository(Profile)
    private readonly profileRepo: Repository<Profile>,
    private readonly blocksService: BlocksService,
    private readonly pushService: PushService,
    private readonly realtime: RealtimeRegistry,
    private readonly mediaStore: MediaStore,
  ) {}

  // ---------------------------------------------------------------- helpers

  private fail(code: string, message: string, status: HttpStatus): never {
    throw new ViroException(code as never, message, status);
  }

  /** Finds or creates the canonical 1:1 conversation for two users. */
  async getOrCreateDm(userA: string, userB: string): Promise<Conversation> {
    const [a, b] = [userA, userB].sort();
    const dmKey = `${a}:${b}`;
    const existing = await this.convRepo.findOne({ where: { dmKey } });
    if (existing) return existing;
    try {
      const conv = await this.convRepo.save(
        this.convRepo.create({ isGroup: false, dmKey, createdBy: userA, kind: 'DM' }),
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
      this.fail('INTERNAL_ERROR', 'Could not open conversation.', HttpStatus.CONFLICT);
    }
  }

  async participantIds(conversationId: string): Promise<string[]> {
    const rows = await this.partRepo.find({ where: { conversationId } });
    return rows.map((r) => r.userId);
  }

  async assertMember(conversationId: string, userId: string): Promise<ConversationParticipant> {
    const part = await this.partRepo.findOne({ where: { conversationId, userId } });
    if (!part) {
      this.fail('FORBIDDEN', 'Not a participant in this conversation.', HttpStatus.FORBIDDEN);
    }
    return part;
  }

  async isMember(conversationId: string, userId: string): Promise<boolean> {
    return !!(await this.partRepo.findOne({ where: { conversationId, userId } }));
  }

  private async loadConversation(conversationId: string): Promise<Conversation> {
    const conv = await this.convRepo.findOne({ where: { id: conversationId } });
    if (!conv) this.fail('NOT_FOUND', 'Conversation not found.', HttpStatus.NOT_FOUND);
    if (conv.expiresAt && conv.expiresAt.getTime() <= Date.now()) {
      this.fail('NOT_FOUND', 'This private session has ended.', HttpStatus.GONE);
    }
    return conv;
  }

  /** True when [m] is visible to [userId] given their participant row. */
  private visibleTo(m: Message, userId: string, part: ConversationParticipant | undefined, conv?: Conversation) {
    if (m.deliverAt && m.senderUserId !== userId) return false;
    if (m.expiresAt && m.expiresAt.getTime() <= Date.now()) return false;
    if (part?.clearedAt && m.createdAt.getTime() <= part.clearedAt.getTime()) return false;
    if (conv?.resetAt && m.createdAt.getTime() < conv.resetAt.getTime() && m.type !== 'SYSTEM') return false;
    return true;
  }

  /**
   * Turns rows into DTOs for one viewer, loading every related table in one
   * query each rather than per message.
   */
  async hydrate(viewerId: string, msgs: Message[]): Promise<MessageDto[]> {
    if (msgs.length === 0) return [];
    const ids = msgs.map((m) => m.id);
    const replyIds = [...new Set(msgs.map((m) => m.replyToId).filter((x): x is string => !!x))];
    const mediaIds = [...new Set(msgs.map((m) => m.mediaId).filter((x): x is string => !!x))];
    const [reactions, replies, media, views, stars] = await Promise.all([
      this.reactionRepo.find({ where: { messageId: In(ids) } }),
      replyIds.length ? this.msgRepo.find({ where: { id: In(replyIds) } }) : Promise.resolve([]),
      mediaIds.length ? this.mediaRepo.find({ where: { id: In(mediaIds) } }) : Promise.resolve([]),
      this.viewRepo.find({ where: { messageId: In(ids) } }),
      this.starRepo.find({ where: { messageId: In(ids), userId: viewerId } }),
    ]);
    const replyById = new Map(replies.map((r) => [r.id, r]));
    const mediaById = new Map(media.map((x) => [x.id, x]));
    const starred = new Set(stars.map((s) => s.messageId));

    return msgs.map((m) => {
      const mine = m.senderUserId === viewerId;
      const msgViews = views.filter((v) => v.messageId === m.id);
      // Sender sees whether it was opened; a recipient sees whether THEY opened it.
      const viewed = m.viewOnce
        ? mine
          ? msgViews.some((v) => v.userId !== viewerId)
          : msgViews.some((v) => v.userId === viewerId)
        : false;
      // A view-once item is gone for the recipient once opened.
      const consumed = m.viewOnce && !mine && viewed;
      const deleted = !!m.deletedAt;
      const mo = m.mediaId ? mediaById.get(m.mediaId) : undefined;
      const reply = m.replyToId ? replyById.get(m.replyToId) : undefined;
      return {
        id: m.id,
        conversationId: m.conversationId,
        senderUserId: m.senderUserId,
        body: deleted || consumed ? null : m.body,
        type: m.type,
        clientMsgId: m.clientMsgId,
        createdAt: iso(m.createdAt)!,
        updatedAt: iso(m.updatedAt)!,
        editedAt: iso(m.editedAt),
        deletedAt: iso(m.deletedAt),
        expiresAt: iso(m.expiresAt),
        deliverAt: iso(m.deliverAt),
        replyTo: reply
          ? {
              id: reply.id,
              senderUserId: reply.senderUserId,
              type: reply.type,
              body: reply.deletedAt || reply.viewOnce ? null : (reply.body ?? '').slice(0, 160),
              deleted: !!reply.deletedAt,
            }
          : null,
        reactions: deleted
          ? []
          : reactions
              .filter((r) => r.messageId === m.id)
              .map((r) => ({ userId: r.userId, emoji: r.emoji })),
        media: mo && !deleted && !consumed ? this.mediaDto(mo) : null,
        viewOnce: m.viewOnce,
        viewed,
        forwarded: m.forwarded,
        starred: starred.has(m.id),
        metadata: m.metadata,
      };
    });
  }

  /** Sends one frame, hydrated for each participant, to all of their devices. */
  private async emitMessage(
    type: 'message.new' | 'message.updated',
    message: Message,
    participantIds: string[],
    opts: { pushIfOffline?: boolean; skipUser?: string } = {},
  ): Promise<void> {
    for (const uid of participantIds) {
      if (uid === opts.skipUser) continue;
      const [dto] = await this.hydrate(uid, [message]);
      const frame = {
        type,
        callId: message.conversationId,
        fromUserId: message.senderUserId,
        conversationId: message.conversationId,
        message: dto,
        payload: { conversationId: message.conversationId, message: dto },
      };
      const delivered = await this.realtime.deliverToUser(uid, frame);
      if (uid === message.senderUserId) continue;
      if (delivered && type === 'message.new') {
        await this.markDelivered(uid, message.conversationId);
      } else if (!delivered && opts.pushIfOffline) {
        // The sender's own name as they set it; the phone shows its saved
        // contact name instead once the app is open.
        const sender = await this.profileRepo.findOne({ where: { userId: message.senderUserId } });
        await this.pushService.sendToUser(uid, {
          title: sender?.displayName?.trim() || 'New message',
          body: this.pushPreview(message),
          data: {
            type: 'message',
            conversationId: message.conversationId,
            messageId: message.id,
            senderUserId: message.senderUserId,
          },
        });
      }
    }
  }

  private pushPreview(m: Message): string {
    if (m.viewOnce) return 'View once message';
    if (m.type === 'VOICE') return '🎤 Voice message';
    if (m.type === 'IMAGE') return m.body ? `📷 ${m.body.slice(0, 100)}` : '📷 Photo';
    if (m.type === 'LOOP') return 'Answered a Loop';
    return (m.body || '').slice(0, 120);
  }

  /** Sends a bare frame to every device of the given users. */
  async emitFrame(userIds: string[], frame: Record<string, unknown>): Promise<void> {
    for (const uid of userIds) {
      await this.realtime.deliverToUser(uid, { callId: frame.conversationId, ...frame, payload: frame });
    }
  }

  // ------------------------------------------------------------------ send

  async sendMessage(senderId: string, senderDeviceId: string | null, input: SendMessageInput) {
    const type = (input.type || 'TEXT').toUpperCase();
    if (!['TEXT', 'VOICE', 'IMAGE', 'LOOP'].includes(type)) {
      this.fail('VALIDATION_ERROR', 'Unsupported message type.', HttpStatus.BAD_REQUEST);
    }
    const body = (input.body || '').trim();
    if (type === 'TEXT' && !body) {
      this.fail('VALIDATION_ERROR', 'Message body is required.', HttpStatus.BAD_REQUEST);
    }
    if ((type === 'VOICE' || type === 'IMAGE') && !input.mediaId) {
      this.fail('VALIDATION_ERROR', 'This message needs its file.', HttpStatus.BAD_REQUEST);
    }

    let conversation: Conversation;
    let recipientIds: string[];
    if (input.conversationId) {
      conversation = await this.loadConversation(input.conversationId);
      await this.assertMember(conversation.id, senderId);
      recipientIds = (await this.participantIds(conversation.id)).filter((id) => id !== senderId);
    } else if (input.toUserId) {
      if (input.toUserId === senderId) {
        this.fail('VALIDATION_ERROR', 'Cannot message yourself.', HttpStatus.BAD_REQUEST);
      }
      conversation = await this.getOrCreateDm(senderId, input.toUserId);
      recipientIds = [input.toUserId];
    } else {
      this.fail('VALIDATION_ERROR', 'toUserId or conversationId is required.', HttpStatus.BAD_REQUEST);
    }
    for (const rid of recipientIds) {
      if (await this.blocksService.isBlocked(senderId, rid)) {
        this.fail('FORBIDDEN', 'This person is unavailable.', HttpStatus.FORBIDDEN);
      }
    }

    // Idempotency on client-supplied id (safe retries / offline outbox flush).
    if (input.clientMsgId) {
      const dup = await this.msgRepo.findOne({
        where: { conversationId: conversation.id, senderUserId: senderId, clientMsgId: input.clientMsgId },
      });
      if (dup) {
        const [dto] = await this.hydrate(senderId, [dup]);
        return { conversationId: conversation.id, message: dto };
      }
    }

    if (input.replyToId) {
      const target = await this.msgRepo.findOne({ where: { id: input.replyToId } });
      if (!target || target.conversationId !== conversation.id) {
        this.fail('VALIDATION_ERROR', 'Reply target not found.', HttpStatus.BAD_REQUEST);
      }
    }
    if (input.mediaId) {
      const media = await this.mediaRepo.findOne({ where: { id: input.mediaId } });
      if (!media || media.ownerUserId !== senderId) {
        this.fail('VALIDATION_ERROR', 'Recording not found.', HttpStatus.BAD_REQUEST);
      }
    }

    let deliverAt: Date | null = null;
    if (input.deliverAt) {
      const at = new Date(input.deliverAt);
      if (Number.isNaN(at.getTime())) {
        this.fail('VALIDATION_ERROR', 'Invalid scheduled time.', HttpStatus.BAD_REQUEST);
      }
      // Anything under a minute away is just "now".
      if (at.getTime() > Date.now() + 60_000) deliverAt = at;
    }

    // Expiry: a private session ends for every message at once; otherwise the
    // conversation's disappearing timer applies from send time.
    let expiresAt: Date | null = null;
    if (conversation.kind === 'PRIVATE' && conversation.expiresAt) {
      expiresAt = conversation.expiresAt;
    } else if (conversation.disappearingSeconds) {
      const base = deliverAt ? deliverAt.getTime() : Date.now();
      expiresAt = new Date(base + conversation.disappearingSeconds * 1000);
    }

    const metadata: Record<string, unknown> = {};
    if (input.effect && /^[a-z_]{1,24}$/.test(input.effect)) metadata.effect = input.effect;

    const now = new Date();
    const message = await this.msgRepo.save(
      this.msgRepo.create({
        conversationId: conversation.id,
        senderUserId: senderId,
        senderDeviceId: senderDeviceId ?? null,
        clientMsgId: input.clientMsgId ?? null,
        type,
        body: body || null,
        updatedAt: now,
        replyToId: input.replyToId ?? null,
        mediaId: input.mediaId ?? null,
        viewOnce: !!input.viewOnce,
        forwarded: !!input.forwarded,
        deliverAt,
        expiresAt,
        metadata: Object.keys(metadata).length ? metadata : null,
      }),
    );

    // A hidden or cleared chat comes back when something new arrives, as in
    // WhatsApp: "delete chat" is not "block".
    await this.partRepo.update({ conversationId: conversation.id }, { hidden: false });
    await this.convRepo.update({ id: conversation.id }, { updatedAt: now });

    if (!deliverAt) {
      if (recipientIds.length > 0) {
        await this.receiptRepo.save(
          recipientIds.map((uid) => this.receiptRepo.create({ messageId: message.id, userId: uid })),
        );
      }
      await this.emitMessage('message.new', message, [senderId, ...recipientIds], { pushIfOffline: true });
    } else {
      // Only the sender's other devices learn about a scheduled message now.
      await this.emitMessage('message.new', message, [senderId]);
    }

    const [dto] = await this.hydrate(senderId, [message]);
    return { conversationId: conversation.id, message: dto };
  }

  /** Posts a system line ("Chat was reset", "Disappearing messages on"). */
  async postSystem(conversationId: string, actorId: string, event: string, body: string) {
    const now = new Date();
    const message = await this.msgRepo.save(
      this.msgRepo.create({
        conversationId,
        senderUserId: actorId,
        type: 'SYSTEM',
        body,
        updatedAt: now,
        metadata: { event },
      }),
    );
    const ids = await this.participantIds(conversationId);
    await this.emitMessage('message.new', message, ids);
    return message;
  }

  /**
   * A Loop event in the conversation timeline ("Loop completed", or an answer
   * to a non-reciprocal Loop). The card itself is rendered from the Loop's
   * state; this row places it in the chat and wakes the other devices.
   */
  async postSystemLoop(conversationId: string, actorId: string, metadata: Record<string, unknown>) {
    const conv = await this.loadConversation(conversationId);
    const now = new Date();
    const message = await this.msgRepo.save(
      this.msgRepo.create({
        conversationId,
        senderUserId: actorId,
        type: 'LOOP',
        body: typeof metadata.title === 'string' ? metadata.title : 'Loop',
        updatedAt: now,
        metadata,
        expiresAt: conv.kind === 'PRIVATE' ? conv.expiresAt : null,
      }),
    );
    await this.convRepo.update({ id: conversationId }, { updatedAt: now });
    await this.emitMessage('message.new', message, await this.participantIds(conversationId));
    return message;
  }

  // --------------------------------------------------------- edit / delete

  private async ownMessage(userId: string, messageId: string): Promise<Message> {
    const m = await this.msgRepo.findOne({ where: { id: messageId } });
    if (!m) this.fail('NOT_FOUND', 'Message not found.', HttpStatus.NOT_FOUND);
    await this.assertMember(m.conversationId, userId);
    return m;
  }

  async editMessage(userId: string, messageId: string, body: string) {
    const m = await this.ownMessage(userId, messageId);
    const text = (body || '').trim();
    if (m.senderUserId !== userId) this.fail('FORBIDDEN', 'You can only edit your own messages.', HttpStatus.FORBIDDEN);
    if (m.type !== 'TEXT' || m.deletedAt) this.fail('VALIDATION_ERROR', 'This message cannot be edited.', HttpStatus.BAD_REQUEST);
    if (!text) this.fail('VALIDATION_ERROR', 'Message body is required.', HttpStatus.BAD_REQUEST);
    if (Date.now() - m.createdAt.getTime() > EDIT_WINDOW_MS) {
      this.fail('VALIDATION_ERROR', 'Messages can only be edited within 15 minutes.', HttpStatus.BAD_REQUEST);
    }
    if (text === m.body) return (await this.hydrate(userId, [m]))[0];
    const now = new Date();
    m.body = text;
    m.editedAt = now;
    m.updatedAt = now;
    await this.msgRepo.save(m);
    await this.emitMessage('message.updated', m, await this.participantIds(m.conversationId));
    return (await this.hydrate(userId, [m]))[0];
  }

  async deleteMessage(userId: string, messageId: string, scope: 'me' | 'everyone') {
    const m = await this.ownMessage(userId, messageId);
    if (scope === 'me') {
      await this.hiddenRepo.save(this.hiddenRepo.create({ messageId: m.id, userId }));
      await this.emitFrame([userId], { type: 'message.hidden', conversationId: m.conversationId, messageId: m.id });
      return { ok: true };
    }
    if (m.senderUserId !== userId) {
      this.fail('FORBIDDEN', 'You can only delete your own messages for everyone.', HttpStatus.FORBIDDEN);
    }
    if (m.deletedAt) return { ok: true };
    if (Date.now() - m.createdAt.getTime() > DELETE_FOR_EVERYONE_WINDOW_MS) {
      this.fail('VALIDATION_ERROR', 'Messages can only be deleted for everyone within 48 hours.', HttpStatus.BAD_REQUEST);
    }
    const mediaId = m.mediaId;
    const now = new Date();
    m.body = null;
    m.mediaId = null;
    m.deletedAt = now;
    m.updatedAt = now;
    m.metadata = null;
    await this.msgRepo.save(m);
    await this.reactionRepo.delete({ messageId: m.id });
    if (mediaId) await this.deleteMedia([mediaId]);
    await this.emitMessage('message.updated', m, await this.participantIds(m.conversationId));
    return { ok: true };
  }

  async react(userId: string, messageId: string, emoji: string | null) {
    const m = await this.ownMessage(userId, messageId);
    if (m.deletedAt || m.type === 'SYSTEM') this.fail('VALIDATION_ERROR', 'Cannot react to this message.', HttpStatus.BAD_REQUEST);
    if (emoji) {
      const e = emoji.trim();
      if (!e || e.length > 32) this.fail('VALIDATION_ERROR', 'Invalid reaction.', HttpStatus.BAD_REQUEST);
      await this.reactionRepo.save(this.reactionRepo.create({ messageId: m.id, userId, emoji: e }));
    } else {
      await this.reactionRepo.delete({ messageId: m.id, userId });
    }
    m.updatedAt = new Date();
    await this.msgRepo.update({ id: m.id }, { updatedAt: m.updatedAt });
    await this.emitMessage('message.updated', m, await this.participantIds(m.conversationId));
    return (await this.hydrate(userId, [m]))[0];
  }

  async markViewed(userId: string, messageId: string) {
    const m = await this.ownMessage(userId, messageId);
    if (!m.viewOnce || m.senderUserId === userId) return { ok: true };
    await this.viewRepo.save(this.viewRepo.create({ messageId: m.id, userId }));
    m.updatedAt = new Date();
    await this.msgRepo.update({ id: m.id }, { updatedAt: m.updatedAt });
    await this.emitMessage('message.updated', m, await this.participantIds(m.conversationId));
    return { ok: true };
  }

  async star(userId: string, messageId: string, on: boolean) {
    const m = await this.ownMessage(userId, messageId);
    if (on) await this.starRepo.save(this.starRepo.create({ messageId: m.id, userId }));
    else await this.starRepo.delete({ messageId: m.id, userId });
    return { ok: true, starred: on };
  }

  async pin(userId: string, messageId: string, on: boolean) {
    const m = await this.ownMessage(userId, messageId);
    if (on) {
      await this.pinRepo.save(this.pinRepo.create({ conversationId: m.conversationId, messageId: m.id, pinnedBy: userId }));
    } else {
      await this.pinRepo.delete({ conversationId: m.conversationId, messageId: m.id });
    }
    const ids = await this.participantIds(m.conversationId);
    await this.emitFrame(ids, { type: 'conversation.changed', conversationId: m.conversationId });
    return { ok: true, pinned: on };
  }

  // ---------------------------------------------------------- read / sync

  async summarize(userId: string, conv: Conversation, part: ConversationParticipant): Promise<ConversationSummaryDto> {
    const parts = await this.partRepo.find({ where: { conversationId: conv.id } });
    const others = parts.filter((p) => p.userId !== userId);
    const recent = await this.msgRepo.find({
      where: { conversationId: conv.id },
      order: { createdAt: 'DESC' },
      take: 20,
    });
    const hidden = new Set(
      (await this.hiddenRepo.find({ where: { userId, messageId: In(recent.map((m) => m.id).concat(['00000000-0000-0000-0000-000000000000'])) } }))
        .map((h) => h.messageId),
    );
    const visible = recent.filter((m) => !hidden.has(m.id) && this.visibleTo(m, userId, part, conv));
    const last = visible[0];
    const unreadSince = part.lastReadAt ?? new Date(0);
    const unread = visible.filter(
      (m) => m.senderUserId !== userId && m.type !== 'SYSTEM' && m.createdAt > unreadSince,
    ).length;
    const minOf = (xs: (Date | null)[]) => {
      const ds = xs.filter((x): x is Date => !!x);
      if (ds.length === 0 || ds.length < xs.length) return null;
      return new Date(Math.min(...ds.map((d) => d.getTime())));
    };
    const pins = await this.pinRepo.find({ where: { conversationId: conv.id }, order: { createdAt: 'DESC' } });
    return {
      id: conv.id,
      kind: conv.kind,
      isGroup: conv.isGroup,
      title: conv.title,
      participants: parts.map((p) => p.userId),
      createdBy: conv.createdBy,
      lastMessage: last ? (await this.hydrate(userId, [last]))[0] : null,
      unread,
      updatedAt: iso(conv.updatedAt)!,
      hidden: part.hidden,
      mutedUntil: iso(part.mutedUntil),
      clearedAt: iso(part.clearedAt),
      resetAt: iso(conv.resetAt),
      disappearingSeconds: conv.disappearingSeconds,
      expiresAt: iso(conv.expiresAt),
      peerLastReadAt: iso(minOf(others.map((o) => o.lastReadAt))),
      peerLastDeliveredAt: iso(minOf(others.map((o) => o.lastDeliveredAt ?? o.lastReadAt))),
      pinnedMessageIds: pins.map((p) => p.messageId),
    };
  }

  async listConversations(userId: string): Promise<ConversationSummaryDto[]> {
    const parts = await this.partRepo.find({ where: { userId } });
    if (parts.length === 0) return [];
    const convs = await this.convRepo.find({
      where: { id: In(parts.map((p) => p.conversationId)) },
      order: { updatedAt: 'DESC' },
    });
    const now = Date.now();
    const out: ConversationSummaryDto[] = [];
    for (const conv of convs) {
      if (conv.expiresAt && conv.expiresAt.getTime() <= now) continue;
      out.push(await this.summarize(userId, conv, parts.find((p) => p.conversationId === conv.id)!));
    }
    return out;
  }

  async conversationSummary(userId: string, conversationId: string) {
    const part = await this.assertMember(conversationId, userId);
    const conv = await this.loadConversation(conversationId);
    return this.summarize(userId, conv, part);
  }

  /** Messages of one conversation, newest page, visible to [userId]. */
  async history(userId: string, conversationId: string, limit = 50, before?: string) {
    const part = await this.assertMember(conversationId, userId);
    const conv = await this.loadConversation(conversationId);
    const qb = this.msgRepo
      .createQueryBuilder('m')
      .where('m.conversation_id = :cid', { cid: conversationId })
      .andWhere('(m.deliver_at IS NULL OR m.sender_user_id = :uid)', { uid: userId })
      .andWhere('(m.expires_at IS NULL OR m.expires_at > NOW())')
      .andWhere('NOT EXISTS (SELECT 1 FROM message_hidden h WHERE h.message_id = m.id AND h.user_id = :uid)', { uid: userId })
      .orderBy('m.created_at', 'DESC')
      .take(Math.min(Math.max(limit, 1), 200));
    if (part.clearedAt) qb.andWhere('m.created_at > :cleared', { cleared: part.clearedAt });
    if (before) qb.andWhere('m.created_at < :before', { before: new Date(before) });
    const msgs = (await qb.getMany()).filter((m) => this.visibleTo(m, userId, part, conv));
    await this.markDelivered(userId, conversationId);
    return this.hydrate(userId, msgs.reverse());
  }

  /**
   * Everything that changed for [userId] since [since]: every live
   * conversation (so a device can drop ones that were erased or expired) and
   * every visible message created or modified after the cursor. This is what
   * makes a missed realtime frame harmless — the next sync picks it up.
   */
  async sync(userId: string, since?: string) {
    const serverTime = new Date();
    const conversations = await this.listConversations(userId);
    const convIds = conversations.map((c) => c.id);
    let messages: MessageDto[] = [];
    let hasMore = false;
    if (convIds.length > 0) {
      if (!since) {
        // First sync on a device: the recent page of each conversation.
        for (const c of conversations) {
          messages.push(...(await this.history(userId, c.id, 60)));
        }
      } else {
        const sinceDate = new Date(since);
        const parts = await this.partRepo.find({ where: { userId } });
        const convs = await this.convRepo.find({ where: { id: In(convIds) } });
        const LIMIT = 500;
        const rows = await this.msgRepo
          .createQueryBuilder('m')
          .where('m.conversation_id IN (:...ids)', { ids: convIds })
          .andWhere('m.updated_at > :since', { since: sinceDate })
          .andWhere('(m.deliver_at IS NULL OR m.sender_user_id = :uid)', { uid: userId })
          .andWhere('NOT EXISTS (SELECT 1 FROM message_hidden h WHERE h.message_id = m.id AND h.user_id = :uid)', { uid: userId })
          .orderBy('m.updated_at', 'ASC')
          .take(LIMIT + 1)
          .getMany();
        hasMore = rows.length > LIMIT;
        const page = rows.slice(0, LIMIT);
        const visible = page.filter((m) =>
          this.visibleTo(
            m,
            userId,
            parts.find((p) => p.conversationId === m.conversationId),
            convs.find((c) => c.id === m.conversationId),
          ),
        );
        messages = await this.hydrate(userId, visible);
        if (hasMore) {
          return { serverTime: iso(page[page.length - 1].updatedAt), conversations, messages, hasMore };
        }
        for (const cid of new Set(visible.map((m) => m.conversationId))) {
          await this.markDelivered(userId, cid);
        }
      }
    }
    return { serverTime: serverTime.toISOString(), conversations, messages, hasMore };
  }

  /** Delivery watermark; tells the other participants their ticks moved. */
  async markDelivered(userId: string, conversationId: string) {
    const now = new Date();
    await this.partRepo.update({ conversationId, userId }, { lastDeliveredAt: now });
    const others = (await this.participantIds(conversationId)).filter((id) => id !== userId);
    await this.emitFrame(others, {
      type: 'message.receipt',
      conversationId,
      userId,
      lastDeliveredAt: now.toISOString(),
    });
  }

  async markRead(userId: string, conversationId: string) {
    await this.assertMember(conversationId, userId);
    const now = new Date();
    await this.partRepo.update({ conversationId, userId }, { lastReadAt: now, lastDeliveredAt: now });
    await this.receiptRepo
      .createQueryBuilder()
      .update()
      .set({ readAt: now, deliveredAt: now })
      .where('user_id = :uid AND read_at IS NULL', { uid: userId })
      .andWhere('message_id IN (SELECT id FROM messages WHERE conversation_id = :cid)', { cid: conversationId })
      .execute();
    const all = await this.participantIds(conversationId);
    await this.emitFrame(all.filter((id) => id !== userId), {
      type: 'message.receipt',
      conversationId,
      userId,
      lastReadAt: now.toISOString(),
      lastDeliveredAt: now.toISOString(),
    });
    // The reader's other devices clear their unread badge.
    await this.emitFrame([userId], { type: 'conversation.read', conversationId, lastReadAt: now.toISOString() });
    return { ok: true };
  }

  // ------------------------------------------------- conversation controls

  async updateSettings(
    userId: string,
    conversationId: string,
    s: { hidden?: boolean; mutedUntil?: string | null; disappearingSeconds?: number | null },
  ) {
    const part = await this.assertMember(conversationId, userId);
    const conv = await this.loadConversation(conversationId);
    if (s.hidden !== undefined) part.hidden = !!s.hidden;
    if (s.mutedUntil !== undefined) part.mutedUntil = s.mutedUntil ? new Date(s.mutedUntil) : null;
    await this.partRepo.save(part);

    if (s.disappearingSeconds !== undefined) {
      const secs = s.disappearingSeconds || null;
      if (secs !== null && !DISAPPEARING_CHOICES.includes(secs)) {
        this.fail('VALIDATION_ERROR', 'Choose 24 hours, 7 days or 90 days.', HttpStatus.BAD_REQUEST);
      }
      if (conv.kind === 'PRIVATE') {
        this.fail('VALIDATION_ERROR', 'A private session already deletes itself.', HttpStatus.BAD_REQUEST);
      }
      if (conv.disappearingSeconds !== secs) {
        conv.disappearingSeconds = secs;
        await this.convRepo.save(conv);
        const label = secs === null ? 'turned off disappearing messages'
          : `turned on disappearing messages (${secs === 86400 ? '24 hours' : secs === 604800 ? '7 days' : '90 days'})`;
        await this.postSystem(conversationId, userId, secs === null ? 'disappearing_off' : 'disappearing_on', label);
      }
    }
    const ids = await this.participantIds(conversationId);
    await this.emitFrame(ids, { type: 'conversation.changed', conversationId });
    return this.summarize(userId, conv, part);
  }

  /** Delete chat — for me only. The other person keeps their copy. */
  async clearForMe(userId: string, conversationId: string) {
    const part = await this.assertMember(conversationId, userId);
    part.clearedAt = new Date();
    await this.partRepo.save(part);
    await this.emitFrame([userId], {
      type: 'conversation.cleared',
      conversationId,
      clearedAt: part.clearedAt.toISOString(),
    });
    return { ok: true, clearedAt: part.clearedAt.toISOString() };
  }

  /**
   * Reset — wipes the whole conversation for BOTH people and keeps the
   * contact, so they start again from zero. Either participant may do it;
   * the other is told by a system line, never silently.
   */
  async reset(userId: string, conversationId: string) {
    await this.assertMember(conversationId, userId);
    const conv = await this.loadConversation(conversationId);
    const media = await this.msgRepo.find({ where: { conversationId }, select: ['id', 'mediaId'] });
    await this.msgRepo.delete({ conversationId });
    await this.pinRepo.delete({ conversationId });
    await this.deleteMedia(media.map((m) => m.mediaId).filter((x): x is string => !!x));
    conv.resetAt = new Date();
    await this.convRepo.save(conv);
    const ids = await this.participantIds(conversationId);
    await this.emitFrame(ids, { type: 'conversation.reset', conversationId, resetAt: conv.resetAt.toISOString() });
    await this.postSystem(conversationId, userId, 'reset', 'reset this chat');
    return { ok: true, resetAt: conv.resetAt.toISOString() };
  }

  /**
   * Erase & disconnect — removes every 1:1 and private conversation the two
   * people share, for both of them, and drops the Viro connection between
   * them. Meant for when two people remove each other.
   */
  async eraseWith(userId: string, otherUserId: string) {
    if (userId === otherUserId) this.fail('VALIDATION_ERROR', 'Invalid person.', HttpStatus.BAD_REQUEST);
    const mine = await this.partRepo.find({ where: { userId } });
    const theirs = await this.partRepo.find({
      where: { userId: otherUserId, conversationId: In(mine.map((p) => p.conversationId).concat(['00000000-0000-0000-0000-000000000000'])) },
    });
    const shared = await this.convRepo.find({
      where: { id: In(theirs.map((p) => p.conversationId).concat(['00000000-0000-0000-0000-000000000000'])), isGroup: false },
    });
    for (const conv of shared) {
      await this.eraseConversation(conv.id, [userId, otherUserId]);
    }
    await this.connectionRepo.delete({ requesterUserId: userId, recipientUserId: otherUserId });
    await this.connectionRepo.delete({ requesterUserId: otherUserId, recipientUserId: userId });
    return { ok: true, erased: shared.length };
  }

  private async eraseConversation(conversationId: string, notify: string[]) {
    const media = await this.msgRepo.find({ where: { conversationId }, select: ['id', 'mediaId'] });
    await this.convRepo.delete({ id: conversationId });
    await this.deleteMedia(media.map((m) => m.mediaId).filter((x): x is string => !!x));
    await this.emitFrame(notify, { type: 'conversation.erased', conversationId });
  }

  /** A temporary conversation that is deleted outright when its time is up. */
  async startPrivate(userId: string, toUserId: string, durationSeconds: number) {
    if (userId === toUserId) this.fail('VALIDATION_ERROR', 'Cannot message yourself.', HttpStatus.BAD_REQUEST);
    const secs = Math.floor(Number(durationSeconds));
    if (!Number.isFinite(secs) || secs < PRIVATE_MIN_SECONDS || secs > PRIVATE_MAX_SECONDS) {
      this.fail('VALIDATION_ERROR', 'Choose between 5 minutes and 30 days.', HttpStatus.BAD_REQUEST);
    }
    if (await this.blocksService.isBlocked(userId, toUserId)) {
      this.fail('FORBIDDEN', 'This person is unavailable.', HttpStatus.FORBIDDEN);
    }
    const conv = await this.convRepo.save(
      this.convRepo.create({
        isGroup: false,
        kind: 'PRIVATE',
        createdBy: userId,
        expiresAt: new Date(Date.now() + secs * 1000),
      }),
    );
    await this.partRepo.save([
      this.partRepo.create({ conversationId: conv.id, userId }),
      this.partRepo.create({ conversationId: conv.id, userId: toUserId }),
    ]);
    const system = await this.msgRepo.save(
      this.msgRepo.create({
        conversationId: conv.id,
        senderUserId: userId,
        type: 'SYSTEM',
        body: 'started a private session',
        updatedAt: new Date(),
        expiresAt: conv.expiresAt,
        metadata: { event: 'private_started', durationSeconds: secs },
      }),
    );
    await this.emitMessage('message.new', system, [userId, toUserId], { pushIfOffline: true });
    const part = (await this.partRepo.findOne({ where: { conversationId: conv.id, userId } }))!;
    return this.summarize(userId, conv, part);
  }

  /** Ends a private session early; either person may. */
  async endPrivate(userId: string, conversationId: string) {
    await this.assertMember(conversationId, userId);
    const conv = await this.convRepo.findOne({ where: { id: conversationId } });
    if (!conv || conv.kind !== 'PRIVATE') this.fail('VALIDATION_ERROR', 'Not a private session.', HttpStatus.BAD_REQUEST);
    await this.eraseConversation(conversationId, await this.participantIds(conversationId));
    return { ok: true };
  }

  // ------------------------------------------------------------- sweeper

  /** Releases due scheduled messages, and deletes expired messages and sessions. */
  async sweep(): Promise<void> {
    // Claim-and-release in one statement so two API replicas cannot both send it.
    const released = returningRows<{ id: string }>(
      await this.msgRepo.query(
        `UPDATE messages SET deliver_at = NULL, created_at = NOW(), updated_at = NOW()
         WHERE deliver_at IS NOT NULL AND deliver_at <= NOW() RETURNING id`,
      ),
    );
    for (const r of released) {
      if (!r?.id) continue;
      const m = await this.msgRepo.findOne({ where: { id: r.id } });
      if (!m) continue;
      const ids = await this.participantIds(m.conversationId);
      const recipients = ids.filter((id) => id !== m.senderUserId);
      if (recipients.length) {
        await this.receiptRepo.save(recipients.map((uid) => this.receiptRepo.create({ messageId: m.id, userId: uid })));
      }
      await this.convRepo.update({ id: m.conversationId }, { updatedAt: new Date() });
      await this.emitMessage('message.new', m, ids, { pushIfOffline: true });
    }

    const expiredConvs = await this.convRepo
      .createQueryBuilder('c')
      .where('c.expires_at IS NOT NULL AND c.expires_at <= NOW()')
      .getMany();
    for (const conv of expiredConvs) {
      await this.eraseConversation(conv.id, await this.participantIds(conv.id));
    }

    const expired = returningRows<{ id: string; conversation_id: string; media_id: string | null }>(
      await this.msgRepo.query(
        `DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at <= NOW()
         RETURNING id, conversation_id, media_id`,
      ),
    ).filter((e) => e?.id && e.conversation_id);
    if (expired.length) {
      await this.deleteMedia(expired.map((e) => e.media_id).filter((x): x is string => !!x));
      const byConv = new Map<string, string[]>();
      for (const e of expired) byConv.set(e.conversation_id, [...(byConv.get(e.conversation_id) ?? []), e.id]);
      for (const [conversationId, messageIds] of byConv) {
        await this.emitFrame(await this.participantIds(conversationId), {
          type: 'message.removed',
          conversationId,
          messageIds,
        });
      }
    }
  }

  // --------------------------------------------------------------- media

  async registerMedia(
    userId: string,
    file: { buffer: Buffer; mimetype: string; size: number },
    meta: { kind: 'VOICE' | 'IMAGE'; durationMs?: number; waveform?: string; width?: number; height?: number },
  ): Promise<MediaDto> {
    const saved = await this.mediaStore.save(file.buffer, file.mimetype);
    const media = await this.mediaRepo.save(
      this.mediaRepo.create({
        ownerUserId: userId,
        kind: meta.kind,
        mime: file.mimetype,
        sizeBytes: file.size,
        durationMs: meta.durationMs && meta.durationMs > 0 ? Math.round(meta.durationMs) : null,
        waveform: meta.waveform && meta.waveform.length <= 2048 ? meta.waveform : null,
        fileName: saved,
        width: meta.width && meta.width > 0 ? meta.width : null,
        height: meta.height && meta.height > 0 ? meta.height : null,
      }),
    );
    return this.mediaDto(media);
  }

  /**
   * The file behind [mediaId], if [userId] may have it: its uploader, or a
   * participant of a conversation holding a live message that references it
   * and that they have not already consumed as view-once.
   */
  async mediaFor(userId: string, mediaId: string): Promise<{ path: string; mime: string } | null> {
    const media = await this.mediaRepo.findOne({ where: { id: mediaId } });
    if (!media) return null;
    if (media.ownerUserId !== userId) {
      const msgs = await this.msgRepo.find({ where: { mediaId } });
      let allowed = false;
      for (const m of msgs) {
        if (m.deletedAt || !(await this.isMember(m.conversationId, userId))) continue;
        if (m.deliverAt && m.senderUserId !== userId) continue;
        if (m.viewOnce && (await this.viewRepo.findOne({ where: { messageId: m.id, userId } }))) continue;
        allowed = true;
        break;
      }
      if (!allowed) allowed = await this.loopMediaAllowed(userId, mediaId);
      if (!allowed) return null;
    }
    const path = this.mediaStore.pathFor(media.fileName);
    return path ? { path, mime: media.mime } : null;
  }

  mediaDto(mo: MediaObject): MediaDto {
    return {
      id: mo.id,
      kind: mo.kind,
      mime: mo.mime,
      sizeBytes: mo.sizeBytes,
      durationMs: mo.durationMs,
      waveform: mo.waveform,
      width: mo.width,
      height: mo.height,
    };
  }

  async deleteMediaIds(ids: string[]) {
    return this.deleteMedia(ids);
  }

  /** A Loop answer's attachment follows the Loop's reveal rule. */
  private async loopMediaAllowed(userId: string, mediaId: string): Promise<boolean> {
    const rows: { user_id: string; period_key: string; loop_id: string; conversation_id: string; reciprocal: boolean }[] =
      await this.msgRepo.query(
        `SELECT a.user_id, a.period_key, a.loop_id, l.conversation_id, l.reciprocal
         FROM loop_answers a JOIN loops l ON l.id = a.loop_id WHERE a.media_id = $1`,
        [mediaId],
      );
    const row = rows[0];
    if (!row || !(await this.isMember(row.conversation_id, userId))) return false;
    if (!row.reciprocal) return true;
    const mine: unknown[] = await this.msgRepo.query(
      'SELECT 1 FROM loop_answers WHERE loop_id = $1 AND period_key = $2 AND user_id = $3',
      [row.loop_id, row.period_key, userId],
    );
    return mine.length > 0;
  }

  private async deleteMedia(ids: string[]) {
    if (ids.length === 0) return;
    const rows = await this.mediaRepo.find({ where: { id: In(ids) } });
    for (const r of rows) this.mediaStore.remove(r.fileName);
    await this.mediaRepo.delete({ id: In(ids) });
  }

  /** Chat "typing…" / "recording voice…" relay, validated against membership. */
  async relayTyping(userId: string, conversationId: string, state: string) {
    // 'present' = has the chat open right now ("together now"); never stored.
    if (!['typing', 'recording', 'idle', 'present', 'left'].includes(state)) return { error: 'invalid_state' };
    if (!(await this.isMember(conversationId, userId))) return { error: 'not_participant' };
    const others = (await this.participantIds(conversationId)).filter((id) => id !== userId);
    for (const uid of others) {
      await this.realtime.deliverToUser(uid, {
        type: 'chat.typing',
        callId: conversationId,
        conversationId,
        userId,
        state,
        payload: { conversationId, userId, state },
      });
    }
    return { ok: true };
  }
}
