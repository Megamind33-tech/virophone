import { lookup } from 'dns/promises';
import { isIP } from 'net';

/**
 * Fetches a URL a user pasted, without letting it reach anything private.
 *
 * A link preview makes the SERVER request an arbitrary address, which is the
 * classic way to probe an internal network (SSRF). Every hop — including each
 * redirect — must be http(s), on a normal port, and resolve only to public
 * addresses. Bodies are capped and requests time out.
 */

const PRIVATE_V4: [number, number][] = [
  [0x00000000, 8], // 0.0.0.0/8
  [0x0a000000, 8], // 10/8
  [0x64400000, 10], // 100.64/10 (CGNAT)
  [0x7f000000, 8], // 127/8
  [0xa9fe0000, 16], // 169.254/16
  [0xac100000, 12], // 172.16/12
  [0xc0000000, 24], // 192.0.0/24
  [0xc0a80000, 16], // 192.168/16
  [0xc6120000, 15], // 198.18/15
  [0xe0000000, 4], // multicast
  [0xf0000000, 4], // reserved
];

function v4ToInt(ip: string): number {
  return ip.split('.').reduce((acc, part) => (acc << 8) + (parseInt(part, 10) & 255), 0) >>> 0;
}

export function isPrivateAddress(ip: string): boolean {
  if (isIP(ip) === 4) {
    const n = v4ToInt(ip);
    return PRIVATE_V4.some(([base, bits]) => (n >>> (32 - bits)) === (base >>> (32 - bits)));
  }
  const v6 = ip.toLowerCase();
  if (v6 === '::' || v6 === '::1') return true;
  if (v6.startsWith('::ffff:')) return isPrivateAddress(v6.slice(7));
  if (/^f[cd]/.test(v6)) return true; // fc00::/7 unique local
  if (/^fe[89ab]/.test(v6)) return true; // fe80::/10 link local
  if (v6.startsWith('ff')) return true; // multicast
  return false;
}

async function assertPublic(url: URL): Promise<void> {
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('unsupported scheme');
  if (url.port && !['80', '443', '8080', '8443'].includes(url.port)) throw new Error('unsupported port');
  if (url.username || url.password) throw new Error('credentials in url');
  const host = url.hostname.replace(/^\[|\]$/g, '');
  if (host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) throw new Error('private host');
  const addrs = isIP(host) ? [{ address: host }] : await lookup(host, { all: true, verbatim: true });
  if (addrs.length === 0 || addrs.some((a) => isPrivateAddress(a.address))) throw new Error('private address');
}

export interface SafeResponse {
  finalUrl: string;
  contentType: string;
  body: Buffer;
}

export async function safeFetch(
  raw: string,
  opts: { maxBytes: number; accept: string; timeoutMs?: number; redirects?: number },
): Promise<SafeResponse> {
  let url = new URL(raw);
  const redirects = opts.redirects ?? 3;
  for (let hop = 0; hop <= redirects; hop++) {
    await assertPublic(url);
    const res = await fetch(url, {
      redirect: 'manual',
      signal: AbortSignal.timeout(opts.timeoutMs ?? 6000),
      headers: {
        Accept: opts.accept,
        // Many sites only fill in Open Graph tags for recognised crawlers.
        'User-Agent': 'Mozilla/5.0 (compatible; ViroLinkPreview/1.0; +https://reach.viro3.online)',
        'Accept-Language': 'en',
      },
    });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get('location');
      if (!loc) throw new Error('redirect without location');
      url = new URL(loc, url);
      continue;
    }
    if (!res.ok || !res.body) throw new Error(`status ${res.status}`);
    const declared = parseInt(res.headers.get('content-length') || '0', 10);
    if (declared > opts.maxBytes * 4) throw new Error('too large');
    const chunks: Buffer[] = [];
    let size = 0;
    const reader = res.body.getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > opts.maxBytes) {
        // Enough for the <head>; stop reading rather than fail.
        chunks.push(Buffer.from(value.subarray(0, Math.max(0, value.byteLength - (size - opts.maxBytes)))));
        await reader.cancel().catch(() => undefined);
        break;
      }
      chunks.push(Buffer.from(value));
    }
    return { finalUrl: url.toString(), contentType: res.headers.get('content-type') || '', body: Buffer.concat(chunks) };
  }
  throw new Error('too many redirects');
}
