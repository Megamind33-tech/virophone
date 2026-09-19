import { BeemOtpProvider } from './beem-otp.provider';

/**
 * The two traps in Beem's API are the point of these tests: a `+` in dest_addr
 * silently never delivers, and a rejection arrives as HTTP 200 with the real
 * outcome in the body. Both fail invisibly in production, which is exactly the
 * kind of thing a test should hold still.
 */
describe('BeemOtpProvider', () => {
  const OLD_ENV = process.env;
  let fetchMock: jest.Mock;

  beforeEach(() => {
    process.env = { ...OLD_ENV };
    process.env.BEEM_API_KEY = 'key-1';
    process.env.BEEM_SECRET_KEY = 'secret-1';
    process.env.BEEM_SOURCE_ADDR = 'VIRO';
    fetchMock = jest.fn();
    (global as unknown as { fetch: unknown }).fetch = fetchMock;
  });

  afterAll(() => {
    process.env = OLD_ENV;
  });

  function ok(body: unknown) {
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify(body),
    };
  }

  it('strips the leading + from dest_addr', async () => {
    fetchMock.mockResolvedValue(ok({ successful: true, code: 100 }));

    await new BeemOtpProvider().sendOtp('+260961582985', '123456');

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.recipients[0].dest_addr).toBe('260961582985');
    // The specific thing that breaks delivery while looking fine.
    expect(body.recipients[0].dest_addr).not.toContain('+');
  });

  it('sends the code in the message and uses the configured sender id', async () => {
    fetchMock.mockResolvedValue(ok({ successful: true, code: 100 }));

    await new BeemOtpProvider().sendOtp('+260977426940', '445566');

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.message).toContain('445566');
    expect(body.source_addr).toBe('VIRO');
  });

  it('authenticates with Basic api_key:secret_key', async () => {
    fetchMock.mockResolvedValue(ok({ successful: true, code: 100 }));

    await new BeemOtpProvider().sendOtp('+260961582985', '111222');

    const headers = fetchMock.mock.calls[0][1].headers;
    const expected = Buffer.from('key-1:secret-1').toString('base64');
    expect(headers.Authorization).toBe(`Basic ${expected}`);
  });

  it('treats a 200 response carrying a rejection as a failure', async () => {
    // Beem's own failure shape: the transport succeeded, the send did not.
    fetchMock.mockResolvedValue(ok({ successful: false, code: 104, message: 'Invalid sender id' }));

    await expect(
      new BeemOtpProvider().sendOtp('+260961582985', '123456'),
    ).rejects.toThrow(/Beem rejected/);
  });

  it('fails when credentials are absent rather than pretending to send', async () => {
    delete process.env.BEEM_API_KEY;

    await expect(
      new BeemOtpProvider().sendOtp('+260961582985', '123456'),
    ).rejects.toThrow(/BEEM_API_KEY/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces a network failure', async () => {
    fetchMock.mockRejectedValue(new Error('ETIMEDOUT'));

    await expect(
      new BeemOtpProvider().sendOtp('+260961582985', '123456'),
    ).rejects.toThrow(/Beem request failed/);
  });

  it('does not log the full phone number', async () => {
    fetchMock.mockResolvedValue(ok({ successful: true, code: 100 }));
    const logs: string[] = [];
    const provider = new BeemOtpProvider();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (provider as any).logger = { log: (m: string) => logs.push(m) };

    await provider.sendOtp('+260961582985', '123456');

    expect(logs.join(' ')).not.toContain('260961582985');
    expect(logs.join(' ')).toContain('****');
  });
});
