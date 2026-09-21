import { applyChoice, applyTimer, noChoice, noTimer, ToolError, withoutPicksOf } from './room-tools';

describe('a kitchen timer everyone can see', () => {
  it('counts down to a server instant, pauses with what is left, resumes from it', () => {
    let t = applyTimer(noTimer('m'), { op: 'START', durationMs: 600_000, label: 'Rice' }, 'natasha', 1_000);
    expect(t).toMatchObject({ status: 'RUNNING', label: 'Rice', endsAt: 601_000, revision: 1 });
    t = applyTimer(t, { op: 'PAUSE' }, 'mosty', 101_000);
    expect(t).toMatchObject({ status: 'PAUSED', remainingMs: 500_000, endsAt: null });
    t = applyTimer(t, { op: 'RESUME' }, 'natasha', 200_000);
    expect(t).toMatchObject({ status: 'RUNNING', endsAt: 700_000 });
  });

  it('adds a minute to a running, a paused and a finished timer', () => {
    let t = applyTimer(noTimer('m'), { op: 'START', durationMs: 60_000 }, 'n', 0);
    expect(applyTimer(t, { op: 'ADD', addMs: 60_000 }, 'n', 10_000).endsAt).toBe(120_000);
    // Already gone off: another minute from now, not from when it rang.
    expect(applyTimer(t, { op: 'ADD', addMs: 60_000 }, 'n', 90_000).endsAt).toBe(150_000);
    t = applyTimer(t, { op: 'PAUSE' }, 'n', 30_000);
    expect(applyTimer(t, { op: 'ADD', addMs: 60_000 }, 'n', 40_000).remainingMs).toBe(90_000);
  });

  it('refuses what a timer does not do', () => {
    expect(() => applyTimer(noTimer('m'), { op: 'START', durationMs: 1_000 }, 'n', 0)).toThrow(ToolError);
    expect(() => applyTimer(noTimer('m'), { op: 'START', durationMs: 5 * 60 * 60 * 1000 }, 'n', 0)).toThrow(ToolError);
    expect(() => applyTimer(noTimer('m'), { op: 'PAUSE' }, 'n', 0)).toThrow(ToolError);
    expect(() => applyTimer(noTimer('m'), { op: 'ADD', addMs: 60_000 }, 'n', 0)).toThrow(ToolError);
    const t = applyTimer(noTimer('m'), { op: 'START', durationMs: 60_000 }, 'n', 0);
    expect(applyTimer(t, { op: 'CANCEL' }, 'n', 0)).toMatchObject({ status: 'NONE', endsAt: null });
  });
});

describe('a question everyone can answer', () => {
  const asked = () => applyChoice(noChoice('m'), { op: 'ASK', question: 'Which dress?', options: ['Blue', 'Green', 'blue', ' '] }, 'natasha');

  it('keeps two to six different options, and everyone can pick, change or take back an answer', () => {
    let c = asked();
    expect(c.options).toEqual([{ id: '1', text: 'Blue' }, { id: '2', text: 'Green' }]);
    c = applyChoice(c, { op: 'PICK', optionId: '2' }, 'mosty');
    c = applyChoice(c, { op: 'PICK', optionId: '1' }, 'chipo');
    c = applyChoice(c, { op: 'PICK', optionId: '1' }, 'mosty');
    expect(c.picks).toEqual({ mosty: '1', chipo: '1' });
    c = applyChoice(c, { op: 'PICK', optionId: null }, 'chipo');
    expect(c.picks).toEqual({ mosty: '1' });
  });

  it('only the person who asked decides', () => {
    const c = asked();
    expect(() => applyChoice(c, { op: 'DECIDE', optionId: '1' }, 'mosty')).toThrow(ToolError);
    expect(applyChoice(c, { op: 'DECIDE', optionId: '2' }, 'natasha')).toMatchObject({ status: 'DECIDED', decided: '2' });
  });

  it('refuses nonsense and lets an answer leave with its person', () => {
    expect(() => applyChoice(noChoice('m'), { op: 'ASK', question: 'Which?', options: ['One'] }, 'n')).toThrow(ToolError);
    expect(() => applyChoice(noChoice('m'), { op: 'ASK', question: ' ', options: ['A', 'B'] }, 'n')).toThrow(ToolError);
    expect(() => applyChoice(noChoice('m'), { op: 'PICK', optionId: '1' }, 'n')).toThrow(ToolError);
    expect(() => applyChoice(asked(), { op: 'PICK', optionId: '9' }, 'n')).toThrow(ToolError);
    const c = applyChoice(asked(), { op: 'PICK', optionId: '1' }, 'mosty');
    expect(withoutPicksOf(c, 'mosty')?.picks).toEqual({});
    expect(withoutPicksOf(c, 'nobody')).toBeNull();
  });
});
