import { publicAvatarUrl } from './avatar.util';

describe('publicAvatarUrl', () => {
  const env = { ...process.env };
  afterEach(() => {
    process.env = { ...env };
  });

  const id = 'dbdd099e-8443-4622-96fa-7e44743d291a';

  it('rewrites a localhost avatar onto the public host', () => {
    delete process.env.API_PUBLIC_URL;
    process.env.API_BASE_URL = 'https://reach.viro3.online';
    expect(publicAvatarUrl(`http://localhost:3001/api/v1/media/avatars/${id}.jpg`)).toBe(
      `https://reach.viro3.online/api/v1/media/avatars/${id}.jpg`,
    );
  });

  it('keeps the cache-busting version', () => {
    process.env.API_PUBLIC_URL = 'https://reach.viro3.online/';
    expect(publicAvatarUrl(`http://localhost:3001/api/v1/media/avatars/${id}.jpg?v=123`)).toBe(
      `https://reach.viro3.online/api/v1/media/avatars/${id}.jpg?v=123`,
    );
  });

  it('leaves external URLs and empty values alone', () => {
    expect(publicAvatarUrl('https://example.com/me.png')).toBe('https://example.com/me.png');
    expect(publicAvatarUrl(null)).toBeNull();
    expect(publicAvatarUrl('  ')).toBeNull();
  });
});
