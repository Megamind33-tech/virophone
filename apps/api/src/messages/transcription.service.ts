import { HttpStatus, Injectable, Logger } from '@nestjs/common';
import { readFileSync } from 'fs';
import { MessagesService } from './messages.service';
import { ViroException } from '../common/exceptions/viro.exception';

/**
 * Voice-note transcripts from a speech model running on our own server
 * (Whisper, in its own container). Audio never leaves Viro's infrastructure
 * for a third party. A note is transcribed once, on request, and the text is
 * shared by everyone in the conversation.
 *
 * The model shares a CPU with everything else on the box, so work is queued:
 * at most two notes at once, and new requests are refused while the queue is
 * already long rather than piling up.
 */
@Injectable()
export class TranscriptionService {
  private readonly logger = new Logger('Transcripts');
  private running = 0;
  private readonly waiting: (() => void)[] = [];
  private readonly inFlight = new Map<string, Promise<{ text: string; language: string | null }>>();

  constructor(private readonly messages: MessagesService) {}

  get enabled(): boolean {
    return !!process.env.WHISPER_URL;
  }

  async transcribe(userId: string, mediaId: string): Promise<{ text: string; language: string | null }> {
    const file = await this.messages.mediaFor(userId, mediaId);
    const media = await this.messages.media(mediaId);
    if (!file || !media) throw new ViroException('NOT_FOUND' as never, 'Voice note not available.', HttpStatus.NOT_FOUND);
    if (media.kind !== 'VOICE') throw new ViroException('VALIDATION_ERROR' as never, 'Only voice notes can be transcribed.', HttpStatus.BAD_REQUEST);
    if (media.transcript !== null && media.transcript !== undefined) {
      return { text: media.transcript, language: media.transcriptLang };
    }
    if (!this.enabled) {
      throw new ViroException('SERVICE_UNAVAILABLE' as never, 'Transcripts are not switched on yet.', HttpStatus.SERVICE_UNAVAILABLE);
    }
    // Two people tapping "Transcribe" on the same note share one job.
    const existing = this.inFlight.get(mediaId);
    if (existing) return existing;
    if (this.waiting.length >= 6) {
      throw new ViroException('SERVICE_UNAVAILABLE' as never, 'Transcripts are busy. Try again in a moment.', HttpStatus.SERVICE_UNAVAILABLE);
    }
    const job = this.run(file.path, file.mime, mediaId).finally(() => this.inFlight.delete(mediaId));
    this.inFlight.set(mediaId, job);
    return job;
  }

  private async slot(): Promise<void> {
    if (this.running < 2) {
      this.running++;
      return;
    }
    await new Promise<void>((resolve) => this.waiting.push(resolve));
    this.running++;
  }

  private release() {
    this.running--;
    this.waiting.shift()?.();
  }

  private async run(path: string, mime: string, mediaId: string) {
    await this.slot();
    try {
      const form = new FormData();
      const ext = mime.includes('ogg') ? 'ogg' : mime.includes('mpeg') ? 'mp3' : 'm4a';
      form.append('audio_file', new Blob([readFileSync(path)], { type: mime }), `note.${ext}`);
      const url = `${process.env.WHISPER_URL!.replace(/\/$/, '')}/asr?task=transcribe&output=json&encode=true`;
      const started = Date.now();
      const res = await fetch(url, { method: 'POST', body: form, signal: AbortSignal.timeout(180_000) });
      if (!res.ok) throw new Error(`whisper ${res.status}`);
      const json = (await res.json()) as { text?: string; language?: string };
      const text = (json.text ?? '').trim();
      const language = json.language?.slice(0, 12) ?? null;
      await this.messages.setTranscript(mediaId, text, language);
      this.logger.log(`TRANSCRIBED media=${mediaId.slice(0, 8)} ms=${Date.now() - started} lang=${language}`);
      return { text, language };
    } catch (e) {
      this.logger.warn(`TRANSCRIBE_FAILED ${(e as Error).message}`);
      throw new ViroException('SERVICE_UNAVAILABLE' as never, "Couldn't transcribe this voice note.", HttpStatus.BAD_GATEWAY);
    } finally {
      this.release();
    }
  }
}
