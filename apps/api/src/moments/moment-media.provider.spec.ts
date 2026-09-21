import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { randomBytes } from 'crypto';
import { kindOf, looksLike, UploadedMediaProvider } from './moment-media.provider';

async function read(stream: NodeJS.ReadableStream): Promise<Buffer> {
  const parts: Buffer[] = [];
  for await (const chunk of stream) parts.push(chunk as Buffer);
  return Buffer.concat(parts);
}

describe('media people bring to a Moment', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'moment-media-'));
  beforeAll(() => { process.env.MOMENT_MEDIA_DIR = dir; });
  afterAll(() => { delete process.env.MOMENT_MEDIA_DIR; fs.rmSync(dir, { recursive: true, force: true }); });

  it('is unreadable on disk, and any part of it reads back exactly — so a film can be seeked', async () => {
    const provider = new UploadedMediaProvider();
    const original = randomBytes(70_001);
    const plain = path.join(provider.incomingDir(), 'upload');
    fs.writeFileSync(plain, original);
    const { fileName, fileKey } = await provider.keep(plain);
    expect(fs.existsSync(plain)).toBe(false);

    const onDisk = fs.readFileSync(path.join(dir, fileName));
    expect(onDisk.length).toBe(original.length);
    expect(onDisk.equals(original)).toBe(false);

    const item = { provider: 'UPLOAD', mime: 'video/mp4', size_bytes: original.length, file_name: fileName, file_key: fileKey };
    expect((await read(provider.open(item).stream)).equals(original)).toBe(true);
    // Ranges that start and end mid-block, across blocks, and at the edges.
    for (const [from, to] of [[0, 0], [1, 15], [15, 16], [17, 4_095], [33_333, 44_444], [70_000, 70_000], [69_990, 999_999]]) {
      const range = provider.open(item, from, to);
      const got = await read(range.stream);
      expect(got.equals(original.subarray(range.start, range.end + 1))).toBe(true);
    }
    provider.remove(item);
    expect(fs.existsSync(path.join(dir, fileName))).toBe(false);
  });

  it('knows a film from a song, and a renamed file from either', () => {
    expect(kindOf('video/mp4')).toBe('VIDEO');
    expect(kindOf('audio/mpeg')).toBe('AUDIO');
    expect(kindOf('application/vnd.android.package-archive')).toBeNull();

    const mp4 = Buffer.from('000000186674797069736f6d', 'hex');
    const mp3 = Buffer.from('ID3\x04\x00\x00', 'latin1');
    const apk = Buffer.from('PK\x03\x04\x14\x00\x08\x00', 'latin1');
    expect(looksLike('video/mp4', mp4)).toBe(true);
    expect(looksLike('audio/mpeg', mp3)).toBe(true);
    expect(looksLike('video/mp4', apk)).toBe(false);
    expect(looksLike('audio/mpeg', apk)).toBe(false);
    expect(looksLike('audio/ogg', Buffer.from('OggS\x00', 'latin1'))).toBe(true);
    expect(looksLike('audio/wav', Buffer.from('RIFF\x00\x00\x00\x00WAVE', 'latin1'))).toBe(true);
  });
});
