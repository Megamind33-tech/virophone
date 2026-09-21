import { Injectable, HttpStatus, Logger } from '@nestjs/common';
import { randomBytes } from 'crypto';
import { publicApiBaseUrl } from '../users/avatar.util';
import { InjectRepository } from '@nestjs/typeorm';
import { In, Repository } from 'typeorm';
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
import { MessageEnvelope } from '../database/entities/e2ee.entity';
import { ViroConnection } from '../database/entities/viro-connection.entity';
import { Profile } from '../database/entities/profile.entity';
import { KeysService } from '../e2ee/keys.service';
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
  poll?: { question: string; options: string[]; multi?: boolean };
  linkPreview?: { url: string; title?: string; description?: string; siteName?: string; mediaId?: string };
  gif?: { url: string; previewUrl?: string; width?: number; height?: number; provider?: string };
  sticker?: { pack: string; id: string };
  /** A shared contact card: a name plus numbers and/or a Viro ID. */
  contact?: { name: string; phones?: string[]; viroId?: string; userId?: string };
  /** A place, or the start of a live location share. */
  location?: { lat: number; lng: number; accuracy?: number; label?: string; liveSeconds?: number };
  /** User ids named with @ in a group message. */
  mentions?: string[];
  /**
   * End-to-end encrypted sends: one sealed copy per recipient device,
   * including the sender's own other devices. The server stores these as they
   * arrive and never holds a key that could open one.
   */
  envelopes?: { deviceId: string; ciphertext: string; type?: number }[];
  /**
   * For a sealed live location: how long the share runs. Where the person is
   * travels inside the ciphertext; the server is told only when to stop
   * carrying updates.
   */
  liveSeconds?: number;
  /**
   * Carried like a message but not one — an encrypted reaction. No push, and
   * no unread badge. Only meaningful for sealed sends: in the clear a
   * reaction has its own endpoint and needs no disguise.
   */
  silent?: boolean;
}

export interface PollDto {
  question: string;
  multi: boolean;
  options: { text: string; votes: number; voters: string[] }[];
  totalVoters: number;
  myVotes: number[];
}

/**
 * How far back the server looks when searching. Bodies are decrypted to match
 * them, so this is deliberately bounded; the phone searches what it holds.
 */
const SEARCH_SCAN_LIMIT = 2000;

/** How long a live location may run for. */
const LIVE_LOCATION_CHOICES = [15 * 60, 60 * 60, 8 * 60 * 60];

/** The most options a poll may have — the only thing a sealed vote is checked against. */
const MAX_POLL_OPTIONS = 12;

/** ~11 cm of precision: enough to find someone, and no more than that. */
const round6 = (n: number) => Math.round(n * 1e6) / 1e6;

/** A sealed text message, generously: the ciphertext of 4000 characters plus its headers. */
const MAX_ENVELOPE_CHARS = 24_000;
/**
 * How many devices one message may be sealed for.
 *
 * A group message is sealed once per member device rather than once for the
 * group, so this has to cover the largest group the app allows — 255 people —
 * with room for their second devices. It is a ceiling, not a target: sender
 * keys would make a group message one ciphertext instead of hundreds, and are
 * the obvious optimisation if groups here ever get large.
 */
const MAX_ENVELOPES = 512;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BASE64_RE = /^[A-Za-z0-9+/=]+$/;

/** GIFs play straight from the provider's CDN; nothing else is accepted. */
const GIF_HOSTS = /^https:\/\/([a-z0-9-]+\.)*(giphy\.com|tenor\.com)\//i;

export interface MediaDto {
  id: string;
  kind: string;
  mime: string;
  sizeBytes: number;
  /** For documents: the sender's own file name. */
  originalName: string | null;
  durationMs: number | null;
  waveform: string | null;
  width: number | null;
  height: number | null;
  transcript: string | null;
  transcriptLang: string | null;
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
  poll: PollDto | null;
  /**
   * For an encrypted message: the sealed copies addressed to the reader's own
   * devices. A device opens the one matching its id and ignores the rest; a
   * device that finds none was not part of the chat when this was sent.
   */
  envelopes: { deviceId: string; ciphertext: string; type: number }[] | null;
  /**
   * Which device sealed it. A reader needs this to find the session the
   * message belongs to, so it is given out for encrypted messages only.
   */
  senderDeviceId: string | null;
  /** When a live location share ends; the where of it is inside the message. */
  liveUntil: string | null;
  /**
   * For a sealed poll: who chose which option number. The phone reads the
   * question and the options out of the message itself and puts the two
   * together — the server only ever holds the numbers.
   */
  pollVotes: { userId: string; optionIndex: number }[] | null;
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
  description: string | null;
  myRole: string;
  /** Read/delivery watermarks of the OTHER participants, for ticks. */
  peerLastReadAt: string | null;
  peerLastDeliveredAt: string | null;
  pinnedMessageIds: string[];
  /** Someone named me with @ in a message I haven't read. */
  mentionedUnread: boolean;
  /** Inbox state, mine alone: the other side is never told. */
  archived: boolean;
  pinnedAt: string | null;
  unreadMarked: boolean;
  /** End-to-end encrypted: the server carries this chat without reading it. */
  encrypted: boolean;
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
  /** Spent prekeys are swept from here, but only now and then. */
  private lastPrekeySweep = 0;

  constructor(
    @InjectRepository(Conversation)
    private readonly convRepo: Repository<Conversation>,
    @InjectRepository(ConversationParticipant)
    private readonly partRepo: Repository<ConversationParticipant>,
    @InjectRepository(Message)
    private readonly msgRepo: Repository<Message>,
    @InjectRepository(MessageMention)
    private readonly mentionRepo: Repository<MessageMention>,
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
    @InjectRepository(PollVote)
    private readonly pollRepo: Repository<PollVote>,
    @InjectRepository(MessageEnvelope)
    private readonly envelopeRepo: Repository<MessageEnvelope>,
    private readonly keysService: KeysService,
    private readonly blocksService: BlocksService,
    private readonly pushService: PushService,
    private readonly realtime: RealtimeRegistry,
    private readonly mediaStore: MediaStore,
  ) {}

  // ---------------------------------------------------------------- helpers

  private fail(code: string, message: string, status: HttpStatus): never {
    throw new ViroException(code as never, message, status);
  }

  /**
   * Reads the sealed copies off a send, or null for an ordinary message.
   *
   * Only their shape is checked — a device id, some ciphertext, and which kind
   * of envelope it is. The contents are meaningless here by design.
   */
  private sealedEnvelopes(
    input: SendMessageInput,
    type: string,
  ): { deviceId: string; ciphertext: string; type: number }[] | null {
    const raw = Array.isArray(input.envelopes) ? input.envelopes : [];
    if (raw.length === 0) return null;
    // Everything a message is made of — its text, the question of a poll, the
    // name of a file, where someone is — travels inside the ciphertext. What
    // the server keeps is the shape of the thing: that there is a message, and
    // a file behind it, and who it is for.
    if (input.poll || input.gif || input.sticker || input.contact || input.location) {
      this.fail(
        'VALIDATION_ERROR',
        'An encrypted message carries its own content; send it sealed.',
        HttpStatus.BAD_REQUEST,
      );
    }
    if (raw.length > MAX_ENVELOPES) {
      this.fail('VALIDATION_ERROR', 'That is too many devices for one message.', HttpStatus.BAD_REQUEST);
    }
    const out: { deviceId: string; ciphertext: string; type: number }[] = [];
    const seen = new Set<string>();
    for (const e of raw) {
      const deviceId = String(e?.deviceId ?? '').trim();
      const ciphertext = String(e?.ciphertext ?? '').trim();
      const kind = Number(e?.type);
      if (!UUID_RE.test(deviceId) || seen.has(deviceId)) {
        this.fail('VALIDATION_ERROR', 'Invalid envelope.', HttpStatus.BAD_REQUEST);
      }
      if (!ciphertext || ciphertext.length > MAX_ENVELOPE_CHARS || !BASE64_RE.test(ciphertext)) {
        this.fail('VALIDATION_ERROR', 'Invalid envelope.', HttpStatus.BAD_REQUEST);
      }
      seen.add(deviceId);
      out.push({ deviceId, ciphertext, type: Number.isInteger(kind) && kind > 0 ? kind : 1 });
    }
    return out;
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
    // A sealed poll looks like any other sealed message from here, so its
    // votes are fetched alongside: the server holds option numbers, never the
    // options themselves.
    const pollIds = msgs.filter((m) => m.type === 'POLL' || m.type === 'ENCRYPTED').map((m) => m.id);
    const votes = pollIds.length ? await this.pollRepo.find({ where: { messageId: In(pollIds) } }) : [];
    // Sealed copies addressed to this reader's own devices. All of them are
    // returned rather than only the asking device's, because a websocket frame
    // goes to every device at once and each picks out its own.
    const sealedIds = msgs.filter((m) => m.type === 'ENCRYPTED').map((m) => m.id);
    const sealed = sealedIds.length
      ? await this.envelopeRepo.find({ where: { messageId: In(sealedIds), userId: viewerId } })
      : [];
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
        poll: m.type === 'POLL' && !deleted ? this.pollDto(m, votes.filter((v) => v.messageId === m.id), viewerId) : null,
        senderDeviceId: m.type === 'ENCRYPTED' ? m.senderDeviceId : null,
        liveUntil: iso(m.liveUntil),
        pollVotes:
          m.type === 'ENCRYPTED' && !deleted
            ? votes
                .filter((v) => v.messageId === m.id)
                .map((v) => ({ userId: v.userId, optionIndex: v.optionIndex }))
            : null,
        envelopes:
          // A view-once message that has been opened gives up nothing more,
          // and the key to its file is inside the envelope.
          m.type === 'ENCRYPTED' && !deleted && !consumed
            ? sealed
                .filter((e) => e.messageId === m.id)
                .map((e) => ({ deviceId: e.deviceId, ciphertext: e.ciphertext, type: e.envelopeType }))
            : null,
      };
    });
  }

  private pollDto(m: Message, votes: PollVote[], viewerId: string): PollDto | null {
    const p = (m.metadata?.poll ?? null) as { question?: string; options?: string[]; multi?: boolean } | null;
    if (!p?.question || !Array.isArray(p.options)) return null;
    return {
      question: p.question,
      multi: !!p.multi,
      options: p.options.map((text, i) => {
        const on = votes.filter((v) => v.optionIndex === i);
        return { text, votes: on.length, voters: on.map((v) => v.userId) };
      }),
      totalVoters: new Set(votes.map((v) => v.userId)).size,
      myVotes: votes.filter((v) => v.userId === viewerId).map((v) => v.optionIndex).sort((a, b) => a - b),
    };
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
      } else if (!delivered && opts.pushIfOffline && !message.silent) {
        // Nobody's phone should light up because someone reacted.
        // The sender's own name as they set it; the phone shows its saved
        // contact name instead once the app is open.
        const sender = await this.profileRepo.findOne({ where: { userId: message.senderUserId } });
        const senderName = sender?.displayName?.trim() || 'Someone';
        const conv = await this.convRepo.findOne({ where: { id: message.conversationId } });
        const group = conv?.isGroup ? conv.title || 'Group' : null;
        // A sealed message has no readable metadata, so who was named lives in
        // its own table — which is exactly why that table exists.
        const mentionsMe = message.type === 'ENCRYPTED'
          ? (await this.mentionRepo.count({ where: { messageId: message.id, userId: uid } })) > 0
          : Array.isArray(message.metadata?.mentions)
            && (message.metadata!.mentions as string[]).includes(uid);
        await this.pushService.sendToUser(uid, {
          title: mentionsMe && group
            ? `${senderName} mentioned you in ${group}`
            : group ?? (sender?.displayName?.trim() || 'New message'),
          body: group ? `${senderName}: ${this.pushPreview(message)}` : this.pushPreview(message),
          data: {
            type: 'message',
            conversationId: message.conversationId,
            messageId: message.id,
            senderUserId: message.senderUserId,
            // A mention reaches someone even in a muted group: that is what @ is for.
            ...(mentionsMe ? { mention: '1' } : {}),
          },
        });
      }
    }
  }

  private pushPreview(m: Message): string {
    // The server cannot read an encrypted message, so it cannot preview one.
    // The phone decrypts on wake and replaces this with the real text.
    if (m.type === 'ENCRYPTED') return 'New message';
    if (m.viewOnce) return 'View once message';
    if (m.type === 'VOICE') return '🎤 Voice message';
    if (m.type === 'IMAGE') return m.body ? `📷 ${m.body.slice(0, 100)}` : '📷 Photo';
    if (m.type === 'LOOP') return 'Answered a Loop';
    if (m.type === 'POLL') return `📊 ${(m.metadata?.poll as { question?: string })?.question ?? 'Poll'}`;
    if (m.type === 'GIF') return 'GIF';
    if (m.type === 'STICKER') return `${m.body ?? ''} Sticker`.trim();
    if (m.type === 'FILE') return `📎 ${(m.metadata?.file as { name?: string })?.name ?? 'Document'}`;
    if (m.type === 'CONTACT') return `👤 ${(m.metadata?.contact as { name?: string })?.name ?? 'Contact'}`;
    if (m.type === 'LOCATION') {
      const live = (m.metadata?.location as { liveUntil?: string })?.liveUntil;
      return live && new Date(live).getTime() > Date.now() ? '📍 Live location' : '📍 Location';
    }
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
    if (!['TEXT', 'VOICE', 'IMAGE', 'LOOP', 'POLL', 'GIF', 'STICKER', 'FILE', 'CONTACT', 'LOCATION'].includes(type)) {
      this.fail('VALIDATION_ERROR', 'Unsupported message type.', HttpStatus.BAD_REQUEST);
    }
    // An end-to-end encrypted send. The content arrives already sealed, once
    // per recipient device, so none of the plaintext handling below applies:
    // there is nothing here for the server to read, validate or preview.
    const sealed = this.sealedEnvelopes(input, type);
    const body = sealed ? '' : (input.body || '').trim();
    if (type === 'TEXT' && !body && !sealed) {
      this.fail('VALIDATION_ERROR', 'Message body is required.', HttpStatus.BAD_REQUEST);
    }
    if ((type === 'VOICE' || type === 'IMAGE' || type === 'FILE') && !input.mediaId) {
      this.fail('VALIDATION_ERROR', 'This message needs its file.', HttpStatus.BAD_REQUEST);
    }
    let location: {
      lat: number;
      lng: number;
      accuracy: number | null;
      label: string | null;
      liveUntil: string | null;
      updatedAt: string;
    } | null = null;
    if (type === 'LOCATION' && !sealed) {
      // A coordinate must actually be a number. JSON has no NaN, so a phone
      // that couldn't read its position sends null — which Number() would
      // otherwise turn into 0, a real place in the Gulf of Guinea.
      const rawLat = input.location?.lat;
      const rawLng = input.location?.lng;
      const lat = typeof rawLat === 'number' ? rawLat : Number.NaN;
      const lng = typeof rawLng === 'number' ? rawLng : Number.NaN;
      if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
        this.fail('VALIDATION_ERROR', 'That location is not valid.', HttpStatus.BAD_REQUEST);
      }
      const seconds = input.location?.liveSeconds;
      if (seconds !== undefined && !LIVE_LOCATION_CHOICES.includes(seconds)) {
        this.fail('VALIDATION_ERROR', 'Share live location for 15 minutes, 1 hour or 8 hours.', HttpStatus.BAD_REQUEST);
      }
      const accuracy = Number(input.location?.accuracy);
      location = {
        lat: round6(lat),
        lng: round6(lng),
        accuracy: Number.isFinite(accuracy) && accuracy > 0 ? Math.round(accuracy) : null,
        label: input.location?.label?.trim().slice(0, 120) || null,
        liveUntil: seconds ? new Date(Date.now() + seconds * 1000).toISOString() : null,
        updatedAt: new Date().toISOString(),
      };
    }
    let contact: { name: string; phones: string[]; viroId: string | null; userId: string | null } | null = null;
    if (type === 'CONTACT' && !sealed) {
      const name = (input.contact?.name || '').trim().slice(0, 100);
      const phones = (input.contact?.phones || [])
        .map((p) => String(p).trim().slice(0, 24))
        .filter(Boolean)
        .slice(0, 10);
      if (!name || (phones.length === 0 && !input.contact?.viroId)) {
        this.fail('VALIDATION_ERROR', 'A shared contact needs a name and a number or Viro ID.', HttpStatus.BAD_REQUEST);
      }
      contact = {
        name,
        phones,
        viroId: input.contact?.viroId ? String(input.contact.viroId).trim().slice(0, 32) : null,
        userId: input.contact?.userId ? String(input.contact.userId).trim().slice(0, 64) : null,
      };
    }
    let poll: { question: string; options: string[]; multi: boolean } | null = null;
    if (type === 'POLL' && !sealed) {
      const question = (input.poll?.question || '').trim().slice(0, 200);
      const options = (input.poll?.options || []).map((o) => String(o).trim().slice(0, 100)).filter(Boolean);
      if (!question || options.length < 2 || options.length > 12 || new Set(options).size !== options.length) {
        this.fail('VALIDATION_ERROR', 'A poll needs a question and 2 to 12 different options.', HttpStatus.BAD_REQUEST);
      }
      poll = { question, options, multi: !!input.poll?.multi };
    }
    if (type === 'GIF' && !sealed && !(input.gif?.url && GIF_HOSTS.test(input.gif.url))) {
      this.fail('VALIDATION_ERROR', 'Unsupported GIF.', HttpStatus.BAD_REQUEST);
    }
    if (type === 'STICKER' && !sealed && !(input.sticker?.pack && input.sticker?.id && body)) {
      this.fail('VALIDATION_ERROR', 'Unsupported sticker.', HttpStatus.BAD_REQUEST);
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
    // A block stops two people messaging each other directly; it does not
    // silence someone in a group they both belong to.
    for (const rid of conversation.isGroup ? [] : recipientIds) {
      if (await this.blocksService.isBlocked(senderId, rid)) {
        this.fail('FORBIDDEN', 'This person is unavailable.', HttpStatus.FORBIDDEN);
      }
    }

    // A chat does not quietly stop being encrypted. Once it is, a client that
    // cannot encrypt — an old build, or the web companion — is refused rather
    // than allowed to drop one readable message into it.
    if (!sealed && conversation.encrypted) {
      this.fail(
        'E2EE_NOT_AVAILABLE',
        'This chat is end-to-end encrypted. Send from a device that can encrypt.',
        HttpStatus.CONFLICT,
      );
    }

    // Every sealed copy must be addressed to a device that belongs in this
    // conversation, and everyone here must have been given one: a message half
    // the chat cannot open would be worse than no message at all.
    let sealedRows: { deviceId: string; userId: string; ciphertext: string; type: number }[] = [];
    if (sealed) {
      const known = await this.keysService.encryptableDevices([senderId, ...recipientIds]);
      const ownerOf = new Map(known.map((d) => [d.deviceId, d.userId]));
      for (const e of sealed) {
        if (!ownerOf.has(e.deviceId)) {
          this.fail('VALIDATION_ERROR', 'That device is not part of this conversation.', HttpStatus.BAD_REQUEST);
        }
      }
      const covered = new Set(sealed.map((e) => ownerOf.get(e.deviceId)));
      for (const uid of recipientIds) {
        if (!known.some((d) => d.userId === uid)) {
          this.fail('E2EE_NOT_AVAILABLE', 'This person cannot receive encrypted messages yet.', HttpStatus.CONFLICT);
        }
        if (!covered.has(uid)) {
          this.fail('VALIDATION_ERROR', 'Everyone in this chat needs their own copy.', HttpStatus.BAD_REQUEST);
        }
      }
      sealedRows = sealed.map((e) => ({ ...e, userId: ownerOf.get(e.deviceId)! }));
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

    // A live share has an end time the server must know, because the server is
    // what stops carrying updates once it passes. For a plaintext location it
    // is in the metadata as well; for a sealed one this column is all there is.
    let liveUntil: Date | null = null;
    const liveSeconds = sealed ? input.liveSeconds : input.location?.liveSeconds;
    if (type === 'LOCATION' && liveSeconds !== undefined && liveSeconds !== null) {
      if (!LIVE_LOCATION_CHOICES.includes(liveSeconds)) {
        this.fail('VALIDATION_ERROR', 'Share live location for 15 minutes, 1 hour or 8 hours.', HttpStatus.BAD_REQUEST);
      }
      liveUntil = new Date(Date.now() + liveSeconds * 1000);
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
    if (poll) metadata.poll = poll;
    if (type === 'GIF' && input.gif) {
      const n = (v: unknown) => (typeof v === 'number' && v > 0 && v < 5000 ? Math.round(v) : null);
      metadata.gif = {
        url: input.gif.url,
        previewUrl: input.gif.previewUrl && GIF_HOSTS.test(input.gif.previewUrl) ? input.gif.previewUrl : input.gif.url,
        width: n(input.gif.width),
        height: n(input.gif.height),
        provider: (input.gif.provider || '').slice(0, 16),
      };
    }
    if (type === 'CONTACT' && contact) {
      metadata.contact = contact;
    }
    if (type === 'LOCATION' && location) {
      metadata.location = location;
    }
    let mentionedUserIds: string[] = [];
    if (input.mentions?.length) {
      // Only people actually in the conversation, and never the sender: an @
      // is a way to reach someone here, not a way to probe who exists.
      const here = new Set(await this.participantIds(conversation.id));
      const mentioned = Array.from(new Set(input.mentions.map(String)))
        .filter((id) => id !== senderId && here.has(id))
        .slice(0, 64);
      if (mentioned.length) {
        metadata.mentions = mentioned;
        mentionedUserIds = mentioned;
      }
    }
    if (type === 'FILE' && input.mediaId && !sealed) {
      // Carried on the message so a notification can name the document
      // without loading the file record.
      const doc = await this.mediaRepo.findOne({ where: { id: input.mediaId, ownerUserId: senderId } });
      if (!doc || doc.kind !== 'FILE') {
        this.fail('VALIDATION_ERROR', 'That document is no longer available.', HttpStatus.BAD_REQUEST);
      }
      metadata.file = { name: doc!.originalName ?? 'Document', mime: doc!.mime, size: doc!.sizeBytes };
    }
    if (type === 'STICKER' && input.sticker) {
      metadata.sticker = { pack: String(input.sticker.pack).slice(0, 24), id: String(input.sticker.id).slice(0, 24) };
    }
    if (input.linkPreview?.url && /^https?:\/\//i.test(input.linkPreview.url) && type === 'TEXT') {
      const lp = input.linkPreview;
      metadata.linkPreview = {
        url: lp.url.slice(0, 1000),
        title: lp.title?.slice(0, 200) ?? null,
        description: lp.description?.slice(0, 400) ?? null,
        siteName: lp.siteName?.slice(0, 80) ?? null,
        mediaId: lp.mediaId && /^[0-9a-f-]{36}$/i.test(lp.mediaId) ? lp.mediaId : null,
      };
    }

    const now = new Date();
    const message = await this.msgRepo.save(
      this.msgRepo.create({
        conversationId: conversation.id,
        senderUserId: senderId,
        senderDeviceId: senderDeviceId ?? null,
        clientMsgId: input.clientMsgId ?? null,
        type: sealed ? 'ENCRYPTED' : type,
        body: body || null,
        updatedAt: now,
        replyToId: input.replyToId ?? null,
        mediaId: input.mediaId ?? null,
        viewOnce: !!input.viewOnce,
        forwarded: !!input.forwarded,
        deliverAt,
        expiresAt,
        liveUntil,
        silent: !!sealed && input.silent === true,
        metadata: sealed ? null : Object.keys(metadata).length ? metadata : null,
      }),
    );

    if (sealedRows.length) {
      await this.envelopeRepo.save(
        sealedRows.map((e) =>
          this.envelopeRepo.create({
            messageId: message.id,
            deviceId: e.deviceId,
            userId: e.userId,
            ciphertext: e.ciphertext,
            envelopeType: e.type,
          }),
        ),
      );
      // From here on this chat is encrypted, and stays that way.
      if (!conversation.encrypted) {
        await this.convRepo.update({ id: conversation.id }, { encrypted: true });
        conversation.encrypted = true;
      }
    }

    // Mentions work in sealed messages too: the rows hold who was named, never
    // the words that named them.
    if (mentionedUserIds.length) {
      await this.mentionRepo.save(
        mentionedUserIds.map((uid) =>
          this.mentionRepo.create({
            messageId: message.id,
            userId: uid,
            conversationId: conversation.id,
            createdAt: message.createdAt,
          }),
        ),
      );
    }

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

  async editMessage(
    userId: string,
    messageId: string,
    body: string,
    envelopes?: { deviceId: string; ciphertext: string; type?: number }[],
  ) {
    const m = await this.ownMessage(userId, messageId);
    const text = (body || '').trim();
    if (m.senderUserId !== userId) this.fail('FORBIDDEN', 'You can only edit your own messages.', HttpStatus.FORBIDDEN);
    // An encrypted message is edited the way it was sent: the new words are
    // sealed again for every device and replace what each one holds. The
    // server swaps ciphertext for ciphertext and learns nothing either time.
    if (m.type === 'ENCRYPTED') {
      if (m.deletedAt) this.fail('VALIDATION_ERROR', 'This message cannot be edited.', HttpStatus.BAD_REQUEST);
      if (Date.now() - m.createdAt.getTime() > EDIT_WINDOW_MS) {
        this.fail('VALIDATION_ERROR', 'Messages can only be edited within 15 minutes.', HttpStatus.BAD_REQUEST);
      }
      const sealed = this.sealedEnvelopes({ envelopes } as SendMessageInput, 'TEXT');
      if (!sealed) this.fail('VALIDATION_ERROR', 'An edit needs its sealed copies.', HttpStatus.BAD_REQUEST);
      const audience = await this.participantIds(m.conversationId);
      const ownerOf = new Map((await this.keysService.encryptableDevices(audience)).map((d) => [d.deviceId, d.userId]));
      for (const e of sealed) {
        if (!ownerOf.has(e.deviceId)) {
          this.fail('VALIDATION_ERROR', 'That device is not part of this conversation.', HttpStatus.BAD_REQUEST);
        }
      }
      await this.envelopeRepo.delete({ messageId: m.id });
      await this.envelopeRepo.save(
        sealed.map((e) =>
          this.envelopeRepo.create({
            messageId: m.id,
            deviceId: e.deviceId,
            userId: ownerOf.get(e.deviceId)!,
            ciphertext: e.ciphertext,
            envelopeType: e.type,
          }),
        ),
      );
      const editedAt = new Date();
      m.editedAt = editedAt;
      m.updatedAt = editedAt;
      await this.msgRepo.update({ id: m.id }, { editedAt, updatedAt: editedAt });
      await this.emitMessage('message.updated', m, audience);
      return (await this.hydrate(userId, [m]))[0];
    }
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
    // Deleted for everyone means gone: the sealed copies go with it, or the
    // ciphertext would sit on the server after the message it holds is gone.
    if (m.type === 'ENCRYPTED') await this.envelopeRepo.delete({ messageId: m.id });
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
    // The inbox shows the last thing said, not the last thing carried.
    const last = visible.find((m) => !m.silent);
    const unreadSince = part.lastReadAt ?? new Date(0);
    const unread = visible.filter(
      // A reaction is carried like a message but is not one: no badge for it.
      (m) => m.senderUserId !== userId && m.type !== 'SYSTEM' && !m.silent && m.createdAt > unreadSince,
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
      description: conv.description,
      myRole: part.role,
      peerLastReadAt: iso(minOf(others.map((o) => o.lastReadAt))),
      peerLastDeliveredAt: iso(minOf(others.map((o) => o.lastDeliveredAt ?? o.lastReadAt))),
      pinnedMessageIds: pins.map((p) => p.messageId),
      mentionedUnread: await this.hasUnreadMention(userId, conv.id, part.lastReadAt),
      archived: part.archivedAt != null,
      pinnedAt: iso(part.pinnedAt),
      unreadMarked: part.unreadMarked,
      encrypted: !!conv.encrypted,
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
    await this.partRepo.update(
      { conversationId, userId },
      { lastReadAt: now, lastDeliveredAt: now, unreadMarked: false },
    );
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

  /**
   * Moves a live location on. Only its sender, only while the share is still
   * running — an expired share can't be quietly resumed.
   */
  /**
   * A new position for a sealed live share.
   *
   * The position itself is sealed again for every device, exactly as the first
   * one was, and replaces what each device is holding. The server moves
   * ciphertext from one shape to another and learns nothing by it.
   */
  async updateSealedLiveLocation(
    userId: string,
    senderDeviceId: string | null,
    messageId: string,
    envelopes: { deviceId: string; ciphertext: string; type?: number }[],
  ) {
    const message = await this.msgRepo.findOne({ where: { id: messageId } });
    if (!message || message.deletedAt) {
      this.fail('NOT_FOUND', 'That location is no longer here.', HttpStatus.NOT_FOUND);
    }
    if (message!.senderUserId !== userId) {
      this.fail('FORBIDDEN', 'Only the person sharing can change it.', HttpStatus.FORBIDDEN);
    }
    if (message!.type !== 'ENCRYPTED' || !message!.liveUntil) {
      this.fail('VALIDATION_ERROR', 'That message is not a live location.', HttpStatus.BAD_REQUEST);
    }
    if (message!.liveUntil!.getTime() <= Date.now()) {
      this.fail('VALIDATION_ERROR', 'That live location has ended.', HttpStatus.BAD_REQUEST);
    }
    const sealed = this.sealedEnvelopes({ envelopes } as SendMessageInput, 'TEXT');
    if (!sealed) this.fail('VALIDATION_ERROR', 'An update needs its sealed copies.', HttpStatus.BAD_REQUEST);

    const audience = await this.participantIds(message!.conversationId);
    const known = await this.keysService.encryptableDevices(audience);
    const ownerOf = new Map(known.map((d) => [d.deviceId, d.userId]));
    for (const e of sealed) {
      if (!ownerOf.has(e.deviceId)) {
        this.fail('VALIDATION_ERROR', 'That device is not part of this conversation.', HttpStatus.BAD_REQUEST);
      }
    }
    // Each device holds one copy of where this person is; a new one replaces it.
    await this.envelopeRepo.delete({ messageId: message!.id });
    await this.envelopeRepo.save(
      sealed.map((e) =>
        this.envelopeRepo.create({
          messageId: message!.id,
          deviceId: e.deviceId,
          userId: ownerOf.get(e.deviceId)!,
          ciphertext: e.ciphertext,
          envelopeType: e.type,
        }),
      ),
    );
    message!.senderDeviceId = senderDeviceId ?? message!.senderDeviceId;
    message!.updatedAt = new Date();
    await this.msgRepo.update(
      { id: message!.id },
      { updatedAt: message!.updatedAt, senderDeviceId: message!.senderDeviceId },
    );
    const hydrated = (await this.hydrate(userId, [message!]))[0];
    await this.emitMessage('message.updated', message!, audience);
    return hydrated;
  }

  async updateLiveLocation(
    userId: string,
    messageId: string,
    point: { lat: number; lng: number; accuracy?: number },
  ) {
    const { message, current } = await this.liveLocationMessage(userId, messageId);
    if (!current.liveUntil || new Date(current.liveUntil).getTime() <= Date.now()) {
      this.fail('VALIDATION_ERROR', 'That live location has ended.', HttpStatus.BAD_REQUEST);
    }
    const lat = typeof point.lat === 'number' ? point.lat : Number.NaN;
    const lng = typeof point.lng === 'number' ? point.lng : Number.NaN;
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
      this.fail('VALIDATION_ERROR', 'That location is not valid.', HttpStatus.BAD_REQUEST);
    }
    const accuracy = Number(point.accuracy);
    message.metadata = {
      ...(message.metadata ?? {}),
      location: {
        ...current,
        lat: round6(lat),
        lng: round6(lng),
        accuracy: Number.isFinite(accuracy) && accuracy > 0 ? Math.round(accuracy) : null,
        updatedAt: new Date().toISOString(),
      },
    };
    return this.saveAndBroadcastLocation(userId, message);
  }

  /** Ends a live location share early. */
  async stopLiveLocation(userId: string, messageId: string) {
    // A sealed share ends by closing the window the server knows about; the
    // last position each device holds is simply never replaced again.
    const sealedShare = await this.msgRepo.findOne({ where: { id: messageId, type: 'ENCRYPTED' } });
    if (sealedShare) {
      if (sealedShare.senderUserId !== userId) {
        this.fail('FORBIDDEN', 'Only the person sharing can change it.', HttpStatus.FORBIDDEN);
      }
      const now = new Date();
      await this.msgRepo.update({ id: sealedShare.id }, { liveUntil: now, updatedAt: now });
      sealedShare.liveUntil = now;
      sealedShare.updatedAt = now;
      await this.emitMessage('message.updated', sealedShare, await this.participantIds(sealedShare.conversationId));
      return (await this.hydrate(userId, [sealedShare]))[0];
    }
    const { message, current } = await this.liveLocationMessage(userId, messageId);
    message.metadata = {
      ...(message.metadata ?? {}),
      location: { ...current, liveUntil: new Date().toISOString(), updatedAt: new Date().toISOString() },
    };
    return this.saveAndBroadcastLocation(userId, message);
  }

  /** Has anyone named me with @ since I last read this conversation? */
  private async hasUnreadMention(userId: string, conversationId: string, lastReadAt: Date | null): Promise<boolean> {
    const found = await this.mentionRepo
      .createQueryBuilder('mm')
      .innerJoin(Message, 'm', 'm.id = mm.message_id')
      .where('mm.user_id = :uid', { uid: userId })
      .andWhere('mm.conversation_id = :cid', { cid: conversationId })
      .andWhere('mm.created_at > :since', { since: lastReadAt ?? new Date(0) })
      .andWhere('m.deleted_at IS NULL')
      .andWhere('m.sender_user_id != :uid', { uid: userId })
      .limit(1)
      .getCount();
    return found > 0;
  }

  private async liveLocationMessage(userId: string, messageId: string) {
    const message = await this.msgRepo.findOne({ where: { id: messageId } });
    if (!message || message.deletedAt) {
      this.fail('NOT_FOUND', 'That location is no longer here.', HttpStatus.NOT_FOUND);
    }
    if (message!.senderUserId !== userId) {
      this.fail('FORBIDDEN', 'Only the person sharing can change it.', HttpStatus.FORBIDDEN);
    }
    if (message!.type !== 'LOCATION') {
      this.fail('VALIDATION_ERROR', 'That message is not a location.', HttpStatus.BAD_REQUEST);
    }
    const current = (message!.metadata?.location ?? {}) as {
      lat?: number;
      lng?: number;
      accuracy?: number | null;
      label?: string | null;
      liveUntil?: string | null;
      updatedAt?: string;
    };
    if (!current.liveUntil) {
      this.fail('VALIDATION_ERROR', 'That location was sent once, not shared live.', HttpStatus.BAD_REQUEST);
    }
    return { message: message!, current };
  }

  private async saveAndBroadcastLocation(userId: string, message: Message) {
    const saved = await this.msgRepo.save(message);
    const ids = await this.participantIds(saved.conversationId);
    const hydrated = (await this.hydrate(userId, [saved]))[0];
    await this.emitFrame(ids, {
      type: 'message.updated',
      conversationId: saved.conversationId,
      message: hydrated,
    });
    return hydrated;
  }

  async updateSettings(
    userId: string,
    conversationId: string,
    s: {
      hidden?: boolean;
      mutedUntil?: string | null;
      disappearingSeconds?: number | null;
      archived?: boolean;
      pinned?: boolean;
      markUnread?: boolean;
    },
  ) {
    const part = await this.assertMember(conversationId, userId);
    const conv = await this.loadConversation(conversationId);
    if (s.hidden !== undefined) part.hidden = !!s.hidden;
    if (s.mutedUntil !== undefined) part.mutedUntil = s.mutedUntil ? new Date(s.mutedUntil) : null;
    if (s.archived !== undefined) part.archivedAt = s.archived ? (part.archivedAt ?? new Date()) : null;
    if (s.pinned !== undefined) part.pinnedAt = s.pinned ? (part.pinnedAt ?? new Date()) : null;
    if (s.markUnread !== undefined) part.unreadMarked = !!s.markUnread;
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

    // Prekeys that were handed out a month ago are dead weight. Once an hour
    // is plenty; the sweeper itself runs every fifteen seconds.
    if (Date.now() - this.lastPrekeySweep > 3600_000) {
      this.lastPrekeySweep = Date.now();
      await this.keysService.sweepConsumedPrekeys();
    }
  }

  // --------------------------------------------------------------- media

  async registerMedia(
    userId: string,
    file: { buffer: Buffer; mimetype: string; size: number },
    meta: {
      kind: 'VOICE' | 'IMAGE' | 'FILE';
      durationMs?: number;
      waveform?: string;
      width?: number;
      height?: number;
      originalName?: string;
      /** Ciphertext: nothing about it may be recorded or interpreted. */
      sealed?: boolean;
    },
  ): Promise<MediaDto> {
    const saved = await this.mediaStore.save(file.buffer, file.mimetype);
    if (meta.sealed) {
      // A sealed file keeps nothing describing it: no name, no mime type, no
      // duration or waveform or dimensions. The recipient gets all of that
      // from inside the message, which is the only place it belongs.
      const opaque = await this.mediaRepo.save(
        this.mediaRepo.create({
          ownerUserId: userId,
          kind: meta.kind,
          mime: 'application/octet-stream',
          sizeBytes: file.size,
          fileName: saved,
          sealed: true,
        }),
      );
      return this.mediaDto(opaque);
    }
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
        originalName: meta.originalName ? meta.originalName.slice(0, 255) : null,
      }),
    );
    return this.mediaDto(media);
  }

  /**
   * The file behind [mediaId], if [userId] may have it: its uploader, or a
   * participant of a conversation holding a live message that references it
   * and that they have not already consumed as view-once.
   */
  async mediaFor(
    userId: string,
    mediaId: string,
  ): Promise<{ data: Buffer; mime: string; originalName: string | null } | null> {
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
      if (!allowed) allowed = await this.sharedMediaAllowed(userId, mediaId);
      if (!allowed) return null;
    }
    const data = this.mediaStore.read(media.fileName);
    return data ? { data, mime: media.mime, originalName: media.originalName ?? null } : null;
  }

  mediaDto(mo: MediaObject): MediaDto {
    return {
      id: mo.id,
      kind: mo.kind,
      mime: mo.mime,
      sizeBytes: mo.sizeBytes,
      originalName: mo.originalName ?? null,
      durationMs: mo.durationMs,
      waveform: mo.waveform,
      width: mo.width,
      height: mo.height,
      transcript: mo.transcript ?? null,
      transcriptLang: mo.transcriptLang ?? null,
    };
  }

  async deleteMediaIds(ids: string[]) {
    return this.deleteMedia(ids);
  }

  /** Link-preview thumbnails and group photos: visible to the conversation's members. */
  private async sharedMediaAllowed(userId: string, mediaId: string): Promise<boolean> {
    const rows: { conversation_id: string }[] = await this.msgRepo.query(
      `SELECT conversation_id FROM messages WHERE metadata -> 'linkPreview' ->> 'mediaId' = $1::text AND deleted_at IS NULL
       UNION SELECT id AS conversation_id FROM conversations WHERE avatar_media_id::text = $1::text LIMIT 20`,
      [mediaId],
    );
    for (const r of rows) if (await this.isMember(r.conversation_id, userId)) return true;
    return false;
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

  // -------------------------------------------------------------- groups

  private async namesOf(userIds: string[]): Promise<string> {
    if (userIds.length === 0) return '';
    const profiles = await this.profileRepo.find({ where: { userId: In(userIds) } });
    const names = userIds.map((id) => profiles.find((p) => p.userId === id)?.displayName?.trim() || 'someone');
    return names.length <= 3 ? names.join(', ') : `${names.slice(0, 3).join(', ')} and ${names.length - 3} more`;
  }

  private async existingUsers(ids: string[]): Promise<string[]> {
    if (ids.length === 0) return [];
    const rows: { id: string }[] = await this.msgRepo.query(
      `SELECT id FROM users WHERE id = ANY($1) AND status = 'ACTIVE'`,
      [ids],
    );
    return rows.map((r) => r.id);
  }

  private async groupAsAdmin(userId: string, conversationId: string) {
    const part = await this.assertMember(conversationId, userId);
    const conv = await this.loadConversation(conversationId);
    if (!conv.isGroup) this.fail('VALIDATION_ERROR', 'Not a group.', HttpStatus.BAD_REQUEST);
    if (part.role !== 'ADMIN') this.fail('FORBIDDEN', 'Only group admins can do that.', HttpStatus.FORBIDDEN);
    return conv;
  }

  async createGroup(creatorId: string, title: string, memberIds: string[], description?: string) {
    const name = (title || '').trim().slice(0, 120);
    if (!name) this.fail('VALIDATION_ERROR', 'Give the group a name.', HttpStatus.BAD_REQUEST);
    const wanted = [...new Set((memberIds || []).filter((id) => id && id !== creatorId))];
    if (wanted.length > 255) this.fail('VALIDATION_ERROR', 'A group can have up to 256 people.', HttpStatus.BAD_REQUEST);
    const members = await this.existingUsers(wanted);
    if (members.length === 0) this.fail('VALIDATION_ERROR', 'Add at least one person on Viro.', HttpStatus.BAD_REQUEST);
    const conv = await this.convRepo.save(
      this.convRepo.create({
        isGroup: true,
        kind: 'GROUP',
        title: name,
        description: description?.trim().slice(0, 300) || null,
        createdBy: creatorId,
      }),
    );
    await this.partRepo.save([
      this.partRepo.create({ conversationId: conv.id, userId: creatorId, role: 'ADMIN' }),
      ...members.map((userId) => this.partRepo.create({ conversationId: conv.id, userId, role: 'MEMBER' })),
    ]);
    await this.postSystem(conv.id, creatorId, 'group_created', `created the group “${name}”`);
    const part = (await this.partRepo.findOne({ where: { conversationId: conv.id, userId: creatorId } }))!;
    return this.summarize(creatorId, conv, part);
  }

  /**
   * The group's shareable link. Admins only: a link is a way into the group,
   * so handing one out is an admin's decision. Made on first use.
   */
  async groupInvite(userId: string, conversationId: string) {
    const conv = await this.groupAsAdmin(userId, conversationId);
    if (!conv.inviteCode) {
      conv.inviteCode = randomBytes(16).toString('base64url').slice(0, 22);
      conv.inviteCreatedAt = new Date();
      conv.inviteCreatedBy = userId;
      await this.convRepo.save(conv);
    }
    return this.inviteDto(conv);
  }

  /** A new link. The old one stops working immediately. */
  async resetGroupInvite(userId: string, conversationId: string) {
    const conv = await this.groupAsAdmin(userId, conversationId);
    conv.inviteCode = randomBytes(16).toString('base64url').slice(0, 22);
    conv.inviteCreatedAt = new Date();
    conv.inviteCreatedBy = userId;
    await this.convRepo.save(conv);
    await this.postSystem(conversationId, userId, 'group_invite_reset', 'reset the group link');
    return this.inviteDto(conv);
  }

  /** No link at all until an admin makes a new one. */
  async revokeGroupInvite(userId: string, conversationId: string) {
    const conv = await this.groupAsAdmin(userId, conversationId);
    conv.inviteCode = null;
    conv.inviteCreatedAt = null;
    conv.inviteCreatedBy = null;
    await this.convRepo.save(conv);
    await this.postSystem(conversationId, userId, 'group_invite_revoked', 'turned off the group link');
    return { code: null, url: null, createdAt: null };
  }

  /** What someone holding a link sees before deciding to join. */
  async groupInvitePreview(userId: string, code: string) {
    const conv = await this.inviteTarget(code);
    const participants = await this.participantIds(conv.id);
    return {
      conversationId: conv.id,
      title: conv.title,
      description: conv.description,
      memberCount: participants.length,
      alreadyMember: participants.includes(userId),
    };
  }

  /** Joins the group behind a link. */
  async joinGroupByInvite(userId: string, code: string) {
    const conv = await this.inviteTarget(code);
    const participants = await this.participantIds(conv.id);
    if (participants.includes(userId)) {
      const mine = (await this.partRepo.findOne({ where: { conversationId: conv.id, userId } }))!;
      return this.summarize(userId, conv, mine);
    }
    if (participants.length >= 256) {
      this.fail('VALIDATION_ERROR', 'That group is full.', HttpStatus.BAD_REQUEST);
    }
    // Someone the group's owner blocked doesn't get in through a link.
    if (conv.createdBy && (await this.blocksService.isBlocked(userId, conv.createdBy))) {
      this.fail('FORBIDDEN', 'You can\'t join that group.', HttpStatus.FORBIDDEN);
    }
    await this.partRepo.save(this.partRepo.create({ conversationId: conv.id, userId, role: 'MEMBER' }));
    await this.postSystem(conv.id, userId, 'group_joined', 'joined using the group link');
    const part = (await this.partRepo.findOne({ where: { conversationId: conv.id, userId } }))!;
    return this.summarize(userId, conv, part);
  }

  private async inviteTarget(code: string): Promise<Conversation> {
    const clean = (code || '').trim();
    const conv = clean ? await this.convRepo.findOne({ where: { inviteCode: clean } }) : null;
    if (!conv || !conv.isGroup) {
      this.fail('NOT_FOUND', 'That group link doesn\'t work any more.', HttpStatus.NOT_FOUND);
    }
    return conv!;
  }

  private inviteDto(conv: Conversation) {
    return {
      code: conv.inviteCode,
      url: conv.inviteCode ? `${publicApiBaseUrl()}/api/v1/invite/g/${conv.inviteCode}` : null,
      createdAt: conv.inviteCreatedAt?.toISOString() ?? null,
    };
  }

  async addMembers(adminId: string, conversationId: string, userIds: string[]) {
    await this.groupAsAdmin(adminId, conversationId);
    const current = new Set(await this.participantIds(conversationId));
    const fresh = await this.existingUsers([...new Set(userIds)].filter((id) => !current.has(id)));
    if (fresh.length === 0) return this.members(adminId, conversationId);
    if (current.size + fresh.length > 256) this.fail('VALIDATION_ERROR', 'A group can have up to 256 people.', HttpStatus.BAD_REQUEST);
    await this.partRepo.save(fresh.map((userId) => this.partRepo.create({ conversationId, userId, role: 'MEMBER' })));
    await this.postSystem(conversationId, adminId, 'group_added', `added ${await this.namesOf(fresh)}`);
    return this.members(adminId, conversationId);
  }

  async removeMember(adminId: string, conversationId: string, userId: string) {
    if (adminId === userId) return this.leaveGroup(adminId, conversationId);
    await this.groupAsAdmin(adminId, conversationId);
    const removed = await this.partRepo.delete({ conversationId, userId });
    if (!removed.affected) return this.members(adminId, conversationId);
    await this.postSystem(conversationId, adminId, 'group_removed', `removed ${await this.namesOf([userId])}`);
    // Gone from their list at once, not at their next sync.
    await this.emitFrame([userId], { type: 'conversation.erased', conversationId });
    return this.members(adminId, conversationId);
  }

  async leaveGroup(userId: string, conversationId: string) {
    const part = await this.assertMember(conversationId, userId);
    const conv = await this.loadConversation(conversationId);
    if (!conv.isGroup) this.fail('VALIDATION_ERROR', 'Not a group.', HttpStatus.BAD_REQUEST);
    await this.partRepo.delete({ conversationId, userId });
    await this.emitFrame([userId], { type: 'conversation.erased', conversationId });
    const rest = await this.partRepo.find({ where: { conversationId }, order: { joinedAt: 'ASC' } });
    if (rest.length === 0) {
      await this.eraseConversation(conversationId, []);
      return { ok: true };
    }
    // A group is never left without an admin.
    if (part.role === 'ADMIN' && !rest.some((p) => p.role === 'ADMIN')) {
      rest[0].role = 'ADMIN';
      await this.partRepo.save(rest[0]);
    }
    await this.postSystem(conversationId, userId, 'group_left', 'left');
    return { ok: true };
  }

  async updateGroup(adminId: string, conversationId: string, patch: { title?: string; description?: string | null }) {
    const conv = await this.groupAsAdmin(adminId, conversationId);
    const title = patch.title?.trim().slice(0, 120);
    if (title && title !== conv.title) {
      conv.title = title;
      await this.convRepo.save(conv);
      await this.postSystem(conversationId, adminId, 'group_renamed', `changed the group name to “${title}”`);
    }
    if (patch.description !== undefined) {
      conv.description = patch.description?.trim().slice(0, 300) || null;
      await this.convRepo.save(conv);
      await this.emitFrame(await this.participantIds(conversationId), { type: 'conversation.changed', conversationId });
    }
    return this.conversationSummary(adminId, conversationId);
  }

  async setRole(adminId: string, conversationId: string, userId: string, role: 'ADMIN' | 'MEMBER') {
    await this.groupAsAdmin(adminId, conversationId);
    const part = await this.partRepo.findOne({ where: { conversationId, userId } });
    if (!part) this.fail('NOT_FOUND', 'Not in this group.', HttpStatus.NOT_FOUND);
    if (role === 'MEMBER') {
      const admins = await this.partRepo.count({ where: { conversationId, role: 'ADMIN' } });
      if (part.role === 'ADMIN' && admins <= 1) this.fail('VALIDATION_ERROR', 'A group needs at least one admin.', HttpStatus.BAD_REQUEST);
    }
    part.role = role;
    await this.partRepo.save(part);
    if (role === 'ADMIN') await this.postSystem(conversationId, adminId, 'group_admin', `made ${await this.namesOf([userId])} an admin`);
    await this.emitFrame(await this.participantIds(conversationId), { type: 'conversation.changed', conversationId });
    return this.members(adminId, conversationId);
  }

  async members(userId: string, conversationId: string) {
    await this.assertMember(conversationId, userId);
    const parts = await this.partRepo.find({ where: { conversationId }, order: { joinedAt: 'ASC' } });
    const profiles = await this.profileRepo.find({ where: { userId: In(parts.map((p) => p.userId)) } });
    return parts.map((p) => ({
      userId: p.userId,
      role: p.role,
      displayName: profiles.find((x) => x.userId === p.userId)?.displayName ?? null,
      joinedAt: iso(p.joinedAt),
    }));
  }

  // --------------------------------------------------------------- polls

  async vote(userId: string, messageId: string, options: number[]) {
    const m = await this.ownMessage(userId, messageId);
    const encrypted = m.type === 'ENCRYPTED';
    if ((m.type !== 'POLL' && !encrypted) || m.deletedAt) {
      this.fail('VALIDATION_ERROR', 'Not a poll.', HttpStatus.BAD_REQUEST);
    }
    const chosen = [...new Set((options || []).map((o) => Math.floor(Number(o))))];
    if (encrypted) {
      // The question and its options are sealed, so the server cannot check a
      // vote against them — only that it is a plausible option number. The
      // phones, which can read the poll, do the rest.
      if (chosen.some((i) => !(i >= 0 && i < MAX_POLL_OPTIONS))) {
        this.fail('VALIDATION_ERROR', 'Invalid option.', HttpStatus.BAD_REQUEST);
      }
    } else {
      const poll = m.metadata?.poll as { options?: string[]; multi?: boolean } | undefined;
      const count = poll?.options?.length ?? 0;
      if (chosen.some((i) => !(i >= 0 && i < count))) this.fail('VALIDATION_ERROR', 'Invalid option.', HttpStatus.BAD_REQUEST);
      if (!poll?.multi && chosen.length > 1) this.fail('VALIDATION_ERROR', 'Choose one option.', HttpStatus.BAD_REQUEST);
    }
    await this.pollRepo.delete({ messageId: m.id, userId });
    if (chosen.length) {
      await this.pollRepo.save(chosen.map((optionIndex) => this.pollRepo.create({ messageId: m.id, userId, optionIndex })));
    }
    m.updatedAt = new Date();
    await this.msgRepo.update({ id: m.id }, { updatedAt: m.updatedAt });
    await this.emitMessage('message.updated', m, await this.participantIds(m.conversationId));
    return (await this.hydrate(userId, [m]))[0];
  }

  // -------------------------------------------------------------- search

  /** Text search across the user's conversations (or one), newest first. */
  async search(userId: string, q: string, conversationId?: string) {
    const term = (q || '').trim();
    if (term.length < 2) return [];
    const parts = await this.partRepo.find({ where: { userId } });
    let ids = parts.map((p) => p.conversationId);
    if (conversationId) ids = ids.filter((id) => id === conversationId);
    if (ids.length === 0) return [];
    // Message bodies are encrypted at rest, so the database can no longer
    // match them. The newest slice of the person's messages is decrypted here
    // and matched in memory instead. This is the server half of search, and it
    // goes away entirely when messages become end-to-end encrypted — the phone
    // already searches what it holds without asking anyone.
    const needle = term.toLowerCase();
    const candidates = await this.msgRepo
      .createQueryBuilder('m')
      .where('m.conversation_id IN (:...ids)', { ids })
      .andWhere('m.deleted_at IS NULL')
      .andWhere("m.type IN ('TEXT','IMAGE','POLL')")
      .andWhere('(m.deliver_at IS NULL OR m.sender_user_id = :uid)', { uid: userId })
      .andWhere('(m.view_once = FALSE)')
      .andWhere('NOT EXISTS (SELECT 1 FROM message_hidden h WHERE h.message_id = m.id AND h.user_id = :uid)', { uid: userId })
      .orderBy('m.created_at', 'DESC')
      .take(SEARCH_SCAN_LIMIT)
      .getMany();
    const rows = candidates
      .filter((m) => {
        if ((m.body || '').toLowerCase().includes(needle)) return true;
        const question = (m.metadata?.poll as { question?: string })?.question;
        return !!question && question.toLowerCase().includes(needle);
      })
      .slice(0, 60);
    const convs = await this.convRepo.find({ where: { id: In(ids) } });
    const visible = rows.filter((m) =>
      this.visibleTo(m, userId, parts.find((p) => p.conversationId === m.conversationId), convs.find((c) => c.id === m.conversationId)),
    );
    return this.hydrate(userId, visible);
  }

  async setTranscript(mediaId: string, text: string, lang: string | null) {
    await this.mediaRepo.update({ id: mediaId }, { transcript: text, transcriptLang: lang, transcribedAt: new Date() });
    // Touch the messages that carry it so every device picks the text up on sync.
    const msgs = await this.msgRepo.find({ where: { mediaId } });
    for (const m of msgs) {
      m.updatedAt = new Date();
      await this.msgRepo.update({ id: m.id }, { updatedAt: m.updatedAt });
      await this.emitMessage('message.updated', m, await this.participantIds(m.conversationId));
    }
  }

  async media(mediaId: string) {
    return this.mediaRepo.findOne({ where: { id: mediaId } });
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
