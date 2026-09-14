import { normalizeViroId } from './viro-id.util';

describe('ViroIdUtil', () => {
  it('normalizes valid Viro IDs', () => {
    expect(normalizeViroId('@brian.m')).toBe('brian.m');
    expect(normalizeViroId('BRIAN.M')).toBe('brian.m');
    expect(normalizeViroId('@mosty')).toBe('mosty');
    expect(normalizeViroId('user_01')).toBe('user_01');
  });

  it('rejects invalid partial patterns (not exact-ID lookup)', () => {
    expect(normalizeViroId('@brian.')).toBeNull();
    expect(normalizeViroId('@brian..m')).toBeNull();
    expect(normalizeViroId('@bria')).toBe('bria'); // valid exact ID if registered
    expect(normalizeViroId('@brian')).toBe('brian'); // valid exact ID; directory is exact-match only
    expect(normalizeViroId('@.')).toBeNull();
    expect(normalizeViroId('')).toBeNull();
    expect(normalizeViroId('ab')).toBeNull();
  });

  it('rejects unicode lookalikes', () => {
    expect(normalizeViroId('@brıan')).toBeNull(); // Turkish dotless i
    expect(normalizeViroId('@вrian')).toBeNull(); // Cyrillic
  });

  it('rejects invalid characters', () => {
    expect(normalizeViroId('@invalid!char')).toBeNull();
    expect(normalizeViroId('@user name')).toBeNull();
  });

  it('rejects consecutive dots', () => {
    expect(normalizeViroId('@user..name')).toBeNull();
  });
});
