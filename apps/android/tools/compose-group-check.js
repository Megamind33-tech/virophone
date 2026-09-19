// Compose group-balance check (regression guard for the 0.4.62 Stack.pop crash).
//
// The Compose compiler (1.5.x) miscompiles an early `return@Column` (or any early
// return out of an inline layout lambda): one path ends one group too many, and the
// first recomposition that takes that path crashes with
//   IndexOutOfBoundsException: Index -1 ... at androidx.compose.runtime.Stack.pop
//
// This walks every branch of every compiled composable and fails if any path leaves
// the group stack unbalanced. Run after a build:
//   ./gradlew :app:assembleDebug && node tools/compose-group-check.js
//
// Needs `javap` (any JDK 17+) on PATH or in JAVA_HOME.
const fs = require('fs'), path = require('path');
const only = process.argv[2]; // optional: check only methods whose Class.method contains this
const { execFileSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const classDirs = ['app', 'core/designsystem', 'feature/auth']
  .map(m => path.join(root, m, 'build/tmp/kotlin-classes/debug')).filter(fs.existsSync);
const javap = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'javap') : 'javap';

function walk(d, out) { for (const f of fs.readdirSync(d)) { const p = path.join(d, f);
  if (fs.statSync(p).isDirectory()) walk(p, out); else if (f.endsWith('.class')) out.push(p); } return out; }
const classes = classDirs.flatMap(d => walk(d, []))
  .filter(f => fs.readFileSync(f).includes('androidx/compose/runtime/Composer'));
if (!classes.length) { console.error('No compiled composables found — build the app first.'); process.exit(2); }

let text = '';
for (let i = 0; i < classes.length; i += 40) {
  text += execFileSync(javap, ['-c', '-p', ...classes.slice(i, i + 40)], { maxBuffer: 1 << 30, encoding: 'utf8' });
}
const methods = []; let cur = null; let cls = ''; let sw = null;
for (const line of text.replace(/\r/g, '').split('\n')) {
  const c = /^(?:public |private |protected |final |abstract )*(?:class|interface) ([\w.$]+)/.exec(line);
  if (c) { cls = c[1]; continue; }
  // Any member header: two-space indent, then a declaration ending in ';' (methods, static {}, fields).
  const h = /^  \S/.test(line) && /;\s*$/.test(line) ? (/([\w$<>-]+)\(/.exec(line) || [null, line.trim()]) : null;
  if (h) { cur = { name: h[1], cls, sig: line.trim(), ins: [] }; methods.push(cur); sw = null; continue; }
  if (sw) {
    if (/^\s*}\s*$/.test(line)) { sw = null; continue; }
    const t = /^\s+(-?\w+): (\d+)\s*$/.exec(line);
    if (t) { sw.targets.push(+t[2]); continue; }
  }
  const m = /^\s+(\d+): (\w+)\s*(.*)$/.exec(line);
  if (m && cur) {
    const ins = { off: +m[1], op: m[2], arg: m[3], targets: [] };
    cur.ins.push(ins);
    if (ins.op === 'tableswitch' || ins.op === 'lookupswitch') sw = ins;
  }
}
const plus = /Composer\.(startReplaceableGroup|startRestartGroup|startMovableGroup|startReusableGroup|startDefaults|startReplaceGroup)\b/;
const minus = /Composer\.(endReplaceableGroup|endRestartGroup|endMovableGroup|endReusableGroup|endDefaults|endReplaceGroup)\b/;
const nplus = /Composer\.(startNode|startReusableNode)\b/, nminus = /Composer\.endNode\b/;
let bad = 0, total = 0;
for (const m of methods) {
  if (only && !(m.cls + '.' + m.name).includes(only)) continue;
  if (!m.ins.some(i => /Composer\./.test(i.arg))) continue;
  total++;
  const idx = new Map(m.ins.map((i, k) => [i.off, k]));
  const seen = new Map(); const issues = [];
  const work = [[0, 0, 0]];
  while (work.length) {
    let [k, g, n] = work.pop();
    while (k !== undefined && k < m.ins.length) {
      const i = m.ins[k];
      const st = g + ':' + n;
      if (seen.has(i.off)) { if (seen.get(i.off) !== st) issues.push(`offset ${i.off}: reached with depth ${seen.get(i.off)} and ${st}`); break; }
      seen.set(i.off, st);
      if (plus.test(i.arg)) g++; else if (minus.test(i.arg)) g--;
      if (nplus.test(i.arg)) n++; else if (nminus.test(i.arg)) n--;
      if (g < 0 || n < 0) { issues.push(`offset ${i.off}: group depth went negative (${g}:${n})`); break; }
      if (/return$/.test(i.op)) { if (g !== 0 || n !== 0) issues.push(`${i.op} at ${i.off} with open groups ${g}:${n}`); break; }
      if (i.op === 'athrow') break;
      if (i.op === 'tableswitch' || i.op === 'lookupswitch') { for (const t of i.targets) work.push([idx.get(t), g, n]); break; }
      const t = /^(\d+)/.exec(i.arg);
      if (i.op === 'goto' || i.op === 'goto_w') { k = idx.get(+t[1]); continue; }
      if (/^if/.test(i.op) && t) work.push([idx.get(+t[1]), g, n]);
      k++;
    }
  }
  if (issues.length) { bad++; console.log(`IMBALANCED ${m.cls}.${m.name}`); for (const s of issues.slice(0, 3)) console.log('   ' + s); }
}
const summary = (`checked ${total} composable methods, ${bad} imbalanced`);
console.log(summary);
process.exit(bad ? 1 : 0);
