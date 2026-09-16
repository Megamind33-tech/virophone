import { extensionForMime, validateAvatarMime } from './avatar.util';

describe('avatar.util', () => {
  it('accepts supported mime types', () => {
    expect(() => validateAvatarMime('image/jpeg')).not.toThrow();
    expect(() => validateAvatarMime('image/png')).not.toThrow();
    expect(() => validateAvatarMime('image/webp')).not.toThrow();
  });

  it('rejects unsupported mime types', () => {
    expect(() => validateAvatarMime('application/pdf')).toThrow('INVALID_AVATAR_TYPE');
  });

  it('maps mime to extension', () => {
    expect(extensionForMime('image/png')).toBe('.png');
    expect(extensionForMime('image/webp')).toBe('.webp');
  });
});
