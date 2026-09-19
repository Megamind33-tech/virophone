import { parsePreview } from './link-preview.service';
import { isPrivateAddress } from './safe-fetch';

describe('link previews', () => {
  it('reads Open Graph tags in either attribute order and resolves the image', () => {
    const html = `<html><head>
      <title>Fallback title</title>
      <meta content="Kafue Road Works" property="og:title">
      <meta property="og:description" content="Phase two starts Monday &amp; runs 3 weeks">
      <meta property="og:image" content="/img/cover.jpg">
      <meta property="og:site_name" content="Lusaka Times">
    </head></html>`;
    const p = parsePreview(html, 'https://www.lusakatimes.com/news/1');
    expect(p.title).toBe('Kafue Road Works');
    expect(p.description).toBe('Phase two starts Monday & runs 3 weeks');
    expect(p.siteName).toBe('Lusaka Times');
    expect(p.imageUrl).toBe('https://www.lusakatimes.com/img/cover.jpg');
  });

  it('falls back to <title> and the host name', () => {
    const p = parsePreview('<title>Just a page</title>', 'https://www.example.org/x');
    expect(p.title).toBe('Just a page');
    expect(p.siteName).toBe('example.org');
    expect(p.imageUrl).toBeNull();
  });
});

describe('private addresses are never fetched', () => {
  it.each(['127.0.0.1', '10.1.2.3', '172.20.0.5', '192.168.1.1', '169.254.169.254', '100.64.0.1', '0.0.0.0', '::1', 'fd00::1', 'fe80::1', '::ffff:10.0.0.1'])(
    'blocks %s',
    (ip) => expect(isPrivateAddress(ip)).toBe(true),
  );
  it.each(['8.8.8.8', '102.68.12.4', '2001:4860:4860::8888'])('allows %s', (ip) => expect(isPrivateAddress(ip)).toBe(false));
});
