import { normalizeViroId, formatViroId } from './viro-id.util';

describe('ViroIdUtil', () => {
  it('normalizes Viro IDs', () => {
    expect(normalizeViroId('@brian.m')).toBe('brian.m');
    expect(normalizeViroId('BRIAN.M')).toBe('brian.m');
    expect(normalizeViroId('@mosty')).toBe('mosty');
  });

  it('rejects invalid Viro IDs', () => {
    expect(normalizeViroId('')).toBeNull();
    expect(normalizeViroId('ab')).toBeNull();
    expect(normalizeViroId('@invalid!char')).toBeNull();
  });

  it('formats Viro IDs', () => {
    expect(formatViroId('brian.m')).toBe('@brian.m');
  });
});
