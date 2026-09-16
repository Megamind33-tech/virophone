import * as net from 'net';
import * as tls from 'tls';

type SmtpSocket = net.Socket | tls.TLSSocket;

/**
 * Minimal SMTP client for OTP delivery (no external mail library).
 */
export async function sendSmtpMail(options: {
  host: string;
  port: number;
  user?: string;
  pass?: string;
  from: string;
  to: string;
  subject: string;
  text: string;
}): Promise<void> {
  const { host, port, user, pass, from, to, subject, text } = options;
  let socket: SmtpSocket = await openSocket(host, port);

  const sendLine = (line: string) => {
    socket.write(line + '\r\n');
  };

  const expectCode = async (expected: number | number[]): Promise<void> => {
    const allowed = Array.isArray(expected) ? expected : [expected];
    const code = await readSmtpCode(socket);
    if (!allowed.includes(code)) {
      throw new Error(`SMTP expected ${allowed.join('|')}, got ${code}`);
    }
  };

  try {
    await expectCode(220);
    sendLine(`EHLO ${host}`);
    await expectCode(250);

    if (user && pass) {
      sendLine('AUTH LOGIN');
      await expectCode(334);
      sendLine(Buffer.from(user).toString('base64'));
      await expectCode(334);
      sendLine(Buffer.from(pass).toString('base64'));
      await expectCode(235);
    }

    sendLine(`MAIL FROM:<${from}>`);
    await expectCode(250);
    sendLine(`RCPT TO:<${to}>`);
    await expectCode(250);
    sendLine('DATA');
    await expectCode(354);
    sendLine(`From: ${from}`);
    sendLine(`To: ${to}`);
    sendLine(`Subject: ${subject}`);
    sendLine('MIME-Version: 1.0');
    sendLine('Content-Type: text/plain; charset=utf-8');
    sendLine('');
    for (const line of text.split('\n')) {
      sendLine(line);
    }
    sendLine('.');
    await expectCode(250);
    sendLine('QUIT');
    await expectCode(221);
  } finally {
    socket.end();
  }
}

function openSocket(host: string, port: number): Promise<SmtpSocket> {
  return new Promise((resolve, reject) => {
    if (port === 465) {
      const s = tls.connect({ host, port, servername: host }, () => resolve(s));
      s.on('error', reject);
      return;
    }
    const s = net.connect({ host, port }, () => resolve(s));
    s.on('error', reject);
  });
}

function readSmtpCode(socket: SmtpSocket): Promise<number> {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const onData = (chunk: Buffer) => {
      buffer += chunk.toString();
      const lines = buffer.split('\r\n').filter(Boolean);
      const last = lines[lines.length - 1];
      if (!last || last.length < 3) return;
      if (last.length > 3 && last[3] === '-') return;
      socket.off('data', onData);
      const code = parseInt(last.slice(0, 3), 10);
      if (Number.isNaN(code)) reject(new Error(`Invalid SMTP response: ${last}`));
      else resolve(code);
    };
    socket.on('data', onData);
    socket.on('error', reject);
  });
}
