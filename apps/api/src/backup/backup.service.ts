import { HttpStatus, Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import * as fs from 'fs';
import * as path from 'path';
import { randomUUID } from 'crypto';
import { MessageBackup } from '../database/entities/message-backup.entity';
import { ViroException } from '../common/exceptions/viro.exception';

/** Plenty for years of conversation; media is not in here, it stays on the server. */
export const MAX_BACKUP_BYTES = 64 * 1024 * 1024;

/** What the phone writes at the front of an archive, so the wrong file is refused early. */
const MAGIC = Buffer.from('VIROBAK1');

export interface BackupStatus {
  exists: boolean;
  sizeBytes: number;
  messageCount: number;
  conversationCount: number;
  updatedAt: string | null;
  version: number;
}

/**
 * Where an encrypted backup of someone's chats lives.
 *
 * The phone encrypts its own message store with a recovery key that never
 * leaves it and uploads the result. Everything here treats that as opaque
 * bytes: there is no code path that opens one, because there is no key here
 * to open it with.
 */
@Injectable()
export class BackupService {
  private readonly logger = new Logger('Backup');
  private readonly dir = process.env.BACKUP_UPLOAD_DIR
    || path.join(process.cwd(), 'uploads', 'backups');

  constructor(
    @InjectRepository(MessageBackup) private readonly backups: Repository<MessageBackup>,
  ) {
    fs.mkdirSync(this.dir, { recursive: true });
  }

  private fail(code: 'VALIDATION_ERROR' | 'NOT_FOUND', message: string, status: HttpStatus): never {
    throw new ViroException(code, message, status);
  }

  /** What the person sees on the backup screen: when, how big, how much. */
  async status(userId: string): Promise<BackupStatus> {
    const row = await this.backups.findOne({ where: { userId } });
    if (!row) {
      return { exists: false, sizeBytes: 0, messageCount: 0, conversationCount: 0, updatedAt: null, version: 0 };
    }
    return {
      exists: true,
      sizeBytes: Number(row.sizeBytes),
      messageCount: row.messageCount,
      conversationCount: row.conversationCount,
      updatedAt: row.updatedAt.toISOString(),
      version: row.version,
    };
  }

  /**
   * Replaces this person's backup.
   *
   * The new archive is written under its own name and the row is pointed at
   * it before the old file goes — so a backup interrupted half-way leaves the
   * previous one intact rather than nothing at all.
   */
  async store(
    userId: string,
    deviceId: string | null,
    data: Buffer,
    meta: { messageCount?: number; conversationCount?: number; version?: number },
  ): Promise<BackupStatus> {
    if (!data?.length) this.fail('VALIDATION_ERROR', 'A backup is required.', HttpStatus.BAD_REQUEST);
    if (data.length > MAX_BACKUP_BYTES) {
      this.fail('VALIDATION_ERROR', 'That backup is too large.', HttpStatus.BAD_REQUEST);
    }
    if (!data.subarray(0, MAGIC.length).equals(MAGIC)) {
      // Not an archive this app wrote. Refusing here saves someone restoring
      // a file that was never going to open.
      this.fail('VALIDATION_ERROR', 'That is not a Viro backup.', HttpStatus.BAD_REQUEST);
    }

    const fileName = `${randomUUID()}.viro`;
    fs.writeFileSync(path.join(this.dir, fileName), data);

    const existing = await this.backups.findOne({ where: { userId } });
    const previous = existing?.fileName;
    await this.backups.save(
      this.backups.create({
        ...(existing ?? {}),
        userId,
        fileName,
        sizeBytes: String(data.length),
        messageCount: Math.max(0, Math.floor(meta.messageCount ?? 0)),
        conversationCount: Math.max(0, Math.floor(meta.conversationCount ?? 0)),
        deviceId: deviceId ?? null,
        version: Math.max(1, Math.floor(meta.version ?? 1)),
        updatedAt: new Date(),
      }),
    );
    if (previous && previous !== fileName) this.removeFile(previous);
    return this.status(userId);
  }

  /** The archive itself, for a phone that has the recovery key. */
  async archive(userId: string): Promise<{ data: Buffer; sizeBytes: number } | null> {
    const row = await this.backups.findOne({ where: { userId } });
    if (!row) return null;
    const full = path.join(this.dir, path.basename(row.fileName));
    if (!fs.existsSync(full)) {
      this.logger.warn(`backup row without a file for ${userId}`);
      return null;
    }
    const data = fs.readFileSync(full);
    return { data, sizeBytes: data.length };
  }

  /** Throws the backup away, at the person's request. */
  async remove(userId: string): Promise<{ ok: true }> {
    const row = await this.backups.findOne({ where: { userId } });
    if (row) {
      this.removeFile(row.fileName);
      await this.backups.delete({ userId });
    }
    return { ok: true };
  }

  private removeFile(fileName: string) {
    try {
      fs.unlinkSync(path.join(this.dir, path.basename(fileName)));
    } catch {
      // Already gone, which is the state we wanted.
    }
  }
}
