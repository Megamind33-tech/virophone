import { createHmac } from 'crypto';
import { TurnCredentialService } from './turn-credential.service';

describe('TurnCredentialService', () => {
  const ORIGINAL_ENV = process.env;

  beforeEach(() => {
    process.env = { ...ORIGINAL_ENV };
    delete process.env.TURN_HOST;
    delete process.env.TURN_PORT;
    delete process.env.TURN_TLS_PORT;
    delete process.env.TURN_TRANSPORTS;
    delete process.env.STUN_URLS;
    process.env.TURN_SECRET = 'unit_secret';
  });

  afterAll(() => {
    process.env = ORIGINAL_ENV;
  });

  it('always advertises a public STUN server even without a TURN host', () => {
    const svc = new TurnCredentialService();
    const creds = svc.generateCredentials('user-1', 'device-1');
    expect(creds.urls.some((u) => u.startsWith('stun:'))).toBe(true);
    // No relay URLs when no TURN host is configured.
    expect(creds.urls.some((u) => u.startsWith('turn:'))).toBe(false);
  });

  it('does not advertise a localhost TURN host to clients', () => {
    process.env.TURN_HOST = 'localhost';
    const svc = new TurnCredentialService();
    const creds = svc.generateCredentials('user-1', 'device-1');
    expect(creds.urls.some((u) => u.includes('localhost'))).toBe(false);
    expect(creds.urls.some((u) => u.startsWith('turn:'))).toBe(false);
  });

  it('advertises UDP, TCP and STUN for a routable TURN host', () => {
    process.env.TURN_HOST = 'turn.example.com';
    process.env.TURN_PORT = '3478';
    const svc = new TurnCredentialService();
    const creds = svc.generateCredentials('user-1', 'device-1');
    expect(creds.urls).toContain('stun:turn.example.com:3478');
    expect(creds.urls).toContain('turn:turn.example.com:3478?transport=udp');
    expect(creds.urls).toContain('turn:turn.example.com:3478?transport=tcp');
  });

  it('advertises TURN over TLS when a TLS port is configured', () => {
    process.env.TURN_HOST = 'turn.example.com';
    process.env.TURN_TLS_PORT = '443';
    const svc = new TurnCredentialService();
    const creds = svc.generateCredentials('user-1', 'device-1');
    expect(creds.urls).toContain('turns:turn.example.com:443?transport=tcp');
  });

  it('honours a custom STUN_URLS list', () => {
    process.env.STUN_URLS = 'stun:stun.custom.net:3478';
    const svc = new TurnCredentialService();
    const creds = svc.generateCredentials('user-1', 'device-1');
    expect(creds.urls).toContain('stun:stun.custom.net:3478');
    expect(creds.urls.some((u) => u.includes('l.google.com'))).toBe(false);
  });

  it('issues coturn-compatible REST credentials', () => {
    const svc = new TurnCredentialService();
    const creds = svc.generateCredentials('user-abc', 'device-1');
    // username = <expiry>:<userId>
    const [expiry, userId] = creds.username.split(':');
    expect(userId).toBe('user-abc');
    expect(Number(expiry)).toBeGreaterThan(Math.floor(Date.now() / 1000));
    // credential = base64(HMAC-SHA1(secret, username))
    const expected = createHmac('sha1', 'unit_secret')
      .update(creds.username)
      .digest('base64');
    expect(creds.credential).toBe(expected);
  });
});
