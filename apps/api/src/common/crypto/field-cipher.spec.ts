import {
  decryptBuffer,
  decryptText,
  encryptBuffer,
  encryptText,
  encryptedJsonColumn,
  encryptedTextColumn,
  encryptionEnabled,
  isEncrypted,
  isEncryptedBuffer,
  resetEncryptionKeyCache,
} from './field-cipher';

const KEY = Buffer.alloc(32, 7).toString('base64');
const OTHER_KEY = Buffer.alloc(32, 9).toString('base64');

describe('encryption at rest', () => {
  const original = process.env.MESSAGE_ENCRYPTION_KEY;

  const withKey = (key?: string) => {
    if (key) process.env.MESSAGE_ENCRYPTION_KEY = key;
    else delete process.env.MESSAGE_ENCRYPTION_KEY;
    resetEncryptionKeyCache();
  };

  afterEach(() => {
    if (original === undefined) delete process.env.MESSAGE_ENCRYPTION_KEY;
    else process.env.MESSAGE_ENCRYPTION_KEY = original;
    resetEncryptionKeyCache();
  });

  it('hides the text it stores, and gives it back', () => {
    withKey(KEY);
    const stored = encryptText('Meet me at Cairo Road at 3');
    expect(stored).not.toContain('Cairo Road');
    expect(isEncrypted(stored)).toBe(true);
    expect(decryptText(stored)).toBe('Meet me at Cairo Road at 3');
  });

  it('never writes the same bytes twice for the same message', () => {
    withKey(KEY);
    expect(encryptText('same words')).not.toBe(encryptText('same words'));
  });

  it('refuses text encrypted under another key', () => {
    withKey(KEY);
    const stored = encryptText('private');
    withKey(OTHER_KEY);
    expect(() => decryptText(stored)).toThrow();
  });

  it('refuses text that has been tampered with', () => {
    withKey(KEY);
    const parts = encryptText('transfer 100').split('.');
    const flipped = Buffer.from(parts[3], 'base64');
    flipped[0] ^= 0xff;
    parts[3] = flipped.toString('base64');
    expect(() => decryptText(parts.join('.'))).toThrow();
  });

  it('reads rows written before encryption was switched on', () => {
    withKey(KEY);
    // A plain row from an older release.
    expect(decryptText('an old message')).toBe('an old message');
    expect(encryptedTextColumn.from('an old message')).toBe('an old message');
  });

  it('does nothing at all without a key', () => {
    withKey(undefined);
    expect(encryptionEnabled()).toBe(false);
    expect(encryptText('plain')).toBe('plain');
    expect(encryptedJsonColumn.to({ a: 1 })).toEqual({ a: 1 });
  });

  it('says so rather than guessing when the key is missing for an encrypted row', () => {
    withKey(KEY);
    const stored = encryptText('private');
    const file = encryptBuffer(Buffer.from('private'));
    withKey(undefined);
    expect(() => decryptText(stored)).toThrow(/MESSAGE_ENCRYPTION_KEY/);
    expect(() => decryptBuffer(file)).toThrow(/MESSAGE_ENCRYPTION_KEY/);
  });

  it('refuses a key that is the wrong size', () => {
    withKey(Buffer.alloc(16, 1).toString('base64'));
    expect(() => encryptText('x')).toThrow(/32 bytes/);
  });

  it('carries message metadata through as an object', () => {
    withKey(KEY);
    const metadata = { contact: { name: 'Mwila Banda', phones: ['+260970000000'] } };
    const stored = encryptedJsonColumn.to(metadata);
    expect(JSON.stringify(stored)).not.toContain('260970000000');
    expect(encryptedJsonColumn.from(stored)).toEqual(metadata);
  });

  it('handles nulls at both ends', () => {
    withKey(KEY);
    expect(encryptedTextColumn.to(null)).toBeNull();
    expect(encryptedTextColumn.from(null)).toBeNull();
    expect(encryptedJsonColumn.to(null)).toBeNull();
    expect(encryptedJsonColumn.from(null)).toBeNull();
  });

  it('hides files, and still opens the ones stored plain before', () => {
    withKey(KEY);
    const photo = Buffer.from('\\xff\\xd8\\xff pretend this is a photo');
    const stored = encryptBuffer(photo);
    expect(isEncryptedBuffer(stored)).toBe(true);
    expect(stored.includes('pretend this is a photo')).toBe(false);
    expect(decryptBuffer(stored).equals(photo)).toBe(true);

    const legacy = Buffer.from('an old unencrypted file');
    expect(isEncryptedBuffer(legacy)).toBe(false);
    expect(decryptBuffer(legacy).equals(legacy)).toBe(true);
  });

  it('refuses a file that has been tampered with', () => {
    withKey(KEY);
    const stored = encryptBuffer(Buffer.from('an invoice'));
    stored[stored.length - 20] ^= 0xff;
    expect(() => decryptBuffer(stored)).toThrow();
  });
});
