// Links web-platform-tests files to spec inventory items by static analysis (no test is executed).
//
// Run `npm run wpt:fetch` first, and `npm run generate` (for spec/inventory.json).
// Outputs:
//   spec/wpt-map.json — item id → tests per link strength; test path → metadata
//   spec/WPT.md       — summary: per-spec test coverage, untested core items, unmapped test files
//
// Link strength (strongest first):
//   name — the test file is named after the item: RTCPeerConnection-createOffer.html → RTCPeerConnection.createOffer()
//   ref  — the test (or a worker/helper script it loads) uses the item: `.createOffer(`, `new RTCPeerConnection(`,
//          `{ iceServers: ... }`, `'max-bundle'`, `onicecandidate` / addEventListener('icecandidate').
//          Member names shared by several types count only if the owning type is named in the test.
//   idl  — an idlharness test that checks the item's existence and shape only.
// `ref` is a heuristic: it says a test touches the item, not that it verifies its behaviour.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SPECS, WPT_DIRS } from './scope.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const wptDir = path.join(here, '.cache/wpt');
const specDir = path.join(repoRoot, 'spec');
const sha = fs.readFileSync(path.join(here, 'wpt.lock'), 'utf8').trim();

if (!fs.existsSync(path.join(wptDir, '.git'))) {
  console.error('No WPT checkout: run `npm run wpt:fetch` first.');
  process.exit(1);
}
const inventory = JSON.parse(fs.readFileSync(path.join(specDir, 'inventory.json'), 'utf8'));
const items = inventory.items.filter((i) => i.kind !== 'includes');

// ---------------------------------------------------------------------------
// Inventory index

const itemById = new Map(items.map((i) => [i.id, i]));
const memberName = (i) => i.name.replace(/\(\)$/, '');
const TYPE_KINDS = new Set(['interface', 'mixin', 'dictionary', 'enum', 'typedef', 'callback', 'callback-interface', 'namespace']);
const owners = new Set(items.map((i) => i.owner));
const byOwner = new Map(); // owner → items
const byMember = new Map(); // member name → items
for (const i of items) {
  if (!byOwner.has(i.owner)) byOwner.set(i.owner, []);
  byOwner.get(i.owner).push(i);
  if (TYPE_KINDS.has(i.kind) || i.kind === 'constructor') continue;
  const n = memberName(i);
  if (!byMember.has(n)) byMember.set(n, []);
  byMember.get(n).push(i);
}
// Names too common in JS to count as evidence on their own.
const GENERIC = new Set(('name value length type id kind label state data error message code size width height done then ' +
  'catch push get set has keys values entries start stop close send track stream enabled muted readyState ' +
  'granted denied prompt default none auto user').split(' '));

// A member name is "distinctive" when only one type declares it, counting only members of the same
// category (dictionary field vs. interface member) and ignoring excluded specs for in-scope items.
const category = (i) => (i.kind === 'field' ? 'field' : i.kind === 'enum-value' ? 'enum' : 'member');
const distinctive = (n, item) => new Set((byMember.get(n) ?? [])
  .filter((x) => category(x) === category(item) && (item.tier === 'excluded' || x.tier !== 'excluded'))
  .map((x) => x.owner)).size === 1;
// Type flow for dictionaries: which dictionaries a member's signature mentions, and which dictionaries
// are reachable from a dictionary (its parent and its fields' types). A test that calls getSettings()
// works with MediaTrackSettings, so its `width` is MediaTrackSettings.width, not MediaTrackCapabilities.width.
const dictNames = new Set(items.filter((i) => i.kind === 'dictionary').map((i) => i.owner));
const dictsIn = (sig) => new Set((sig.match(/[A-Za-z_]\w*/g) ?? []).filter((w) => dictNames.has(w)));
const dictEdges = new Map();
for (const i of items) {
  if (i.kind === 'dictionary') {
    const parent = /:\s*(\w+)/.exec(i.signature)?.[1];
    if (!dictEdges.has(i.owner)) dictEdges.set(i.owner, new Set());
    if (parent && dictNames.has(parent)) dictEdges.get(i.owner).add(parent);
  }
}
for (const i of items) if (i.kind === 'field') for (const d of dictsIn(i.signature)) dictEdges.get(i.owner)?.add(d);
function reachDicts(start) {
  const out = new Set(start);
  for (const q = [...start]; q.length;) for (const d of dictEdges.get(q.pop()) ?? []) if (!out.has(d)) { out.add(d); q.push(d); }
  return out;
}
// getStats() returns untyped records; the `type` string picks the dictionary (webrtc-stats §RTCStatsType).
const STATS_TYPE_DICTS = {
  'codec': ['RTCCodecStats'], 'inbound-rtp': ['RTCInboundRtpStreamStats'], 'outbound-rtp': ['RTCOutboundRtpStreamStats'],
  'remote-inbound-rtp': ['RTCRemoteInboundRtpStreamStats'], 'remote-outbound-rtp': ['RTCRemoteOutboundRtpStreamStats'],
  'media-source': ['RTCAudioSourceStats', 'RTCVideoSourceStats'], 'media-playout': ['RTCAudioPlayoutStats'],
  'peer-connection': ['RTCPeerConnectionStats'], 'data-channel': ['RTCDataChannelStats'], 'transport': ['RTCTransportStats'],
  'candidate-pair': ['RTCIceCandidatePairStats'], 'local-candidate': ['RTCIceCandidateStats'],
  'remote-candidate': ['RTCIceCandidateStats'], 'certificate': ['RTCCertificateStats'],
};

// Same, within the item's own spec (e.g. `bytesSent` is in many webrtc-stats dictionaries).
const distinctiveInSpec = (n, item) => new Set((byMember.get(n) ?? [])
  .filter((x) => category(x) === category(item) && x.spec === item.spec)
  .map((x) => x.owner)).size === 1;

// ---------------------------------------------------------------------------
// Test discovery

const SUPPORT_DIRS = /(^|\/)(resources|support|tools|third_party|crashtests)(\/|$)/;
const JS_TEST = /\.(any|window|worker|sharedworker|serviceworker)\.js$/;

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === '.git') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

const tests = [];
for (const [wdir, spec] of Object.entries(WPT_DIRS)) {
  const root = path.join(wptDir, wdir);
  if (!fs.existsSync(root)) continue;
  for (const file of walk(root)) {
    const rel = path.relative(wptDir, file);
    if (SUPPORT_DIRS.test(rel)) continue;
    const text = fs.readFileSync(file, 'utf8');
    const isTest = JS_TEST.test(file) || (file.endsWith('.html') && /testharness\.js/.test(text));
    if (!isTest) continue;
    tests.push({ rel, file, dir: wdir, spec, text });
  }
}

// Inline the worker/helper scripts a test loads by file name (one level deep), so API used
// inside e.g. an encoded-transform worker is attributed to the test that drives it. Shared
// helpers (loaded by many tests, like RTCPeerConnection-helper.js) are not inlined: they would
// link every helper-touched item to every test that loads them.
const MAX_SCRIPT_USERS = 10;
const loadedScripts = (t) => {
  const out = new Set();
  for (const m of t.text.matchAll(/["'`]([^"'`\s]+\.js)(\?[^"'`]*)?["'`]/g)) {
    const p = path.resolve(path.dirname(t.file), m[1]);
    if (p.startsWith(wptDir) && fs.existsSync(p) && !/testharness|testdriver/.test(p)) out.add(p);
  }
  return out;
};
const scriptUsers = new Map();
for (const t of tests) for (const p of (t.scripts = loadedScripts(t))) scriptUsers.set(p, (scriptUsers.get(p) ?? 0) + 1);

function withLoadedScripts(t) {
  let text = t.text;
  for (const p of t.scripts) if (scriptUsers.get(p) <= MAX_SCRIPT_USERS) text += `\n${fs.readFileSync(p, 'utf8')}`;
  return text;
}

// ---------------------------------------------------------------------------
// Matching

const links = new Map(); // item id → { name: Set, ref: Set, idl: Set }
const link = (item, how, rel) => {
  if (!links.has(item.id)) links.set(item.id, { name: new Set(), ref: new Set(), idl: new Set() });
  links.get(item.id)[how].add(rel);
};
const typeItems = (owner) => (byOwner.get(owner) ?? []).filter((i) => TYPE_KINDS.has(i.kind) && i.name === owner);

const testMeta = {};
for (const t of tests) {
  const text = withLoadedScripts(t);
  const before = new Set([...links].filter(([, l]) => l.name.has(t.rel) || l.ref.has(t.rel)).map(([id]) => id));
  const meta = {
    dir: t.dir,
    subtests: (t.text.match(/(^|[^\w.])(promise_test|async_test|test)\s*\(/g) ?? []).length,
    protocol: /(^|\/)protocol\//.test(t.rel) || undefined,
    tentative: /tentative/.test(t.rel) || undefined,
  };

  // idlharness: idl_test(['spec-a', ...], [deps], ...) checks the listed specs' IDL.
  const idl = /idl_test\(\s*\[([^\]]*)\]/.exec(t.text);
  if (idl) {
    const specs = [...idl[1].matchAll(/['"]([^'"]+)['"]/g)].map((m) => m[1]);
    meta.idlharness = specs;
    for (const i of items) if (specs.includes(i.spec)) link(i, 'idl', t.rel);
  }

  // name: <Owner>-<member>-<anything>.html
  const tokens = path.basename(t.rel).split('.')[0].split('-');
  if (owners.has(tokens[0])) {
    const owner = tokens[0];
    for (const i of typeItems(owner)) link(i, 'name', t.rel);
    for (const tok of tokens.slice(1)) {
      for (const i of byOwner.get(owner)) {
        const n = memberName(i);
        if (!TYPE_KINDS.has(i.kind) && (n.toLowerCase() === tok.toLowerCase() || n.toLowerCase() === `on${tok.toLowerCase()}`)) link(i, 'name', t.rel);
      }
      if (tok === 'constructor') for (const i of byOwner.get(owner)) if (i.kind === 'constructor') link(i, 'name', t.rel);
    }
  }

  // ref: what the code touches
  const idents = new Set(text.match(/[A-Za-z_$][\w$]*/g) ?? []);
  const dotRefs = new Set([...text.matchAll(/\.\s*([A-Za-z_$][\w$]*)/g)].map((m) => m[1]));
  const keys = new Set([...text.matchAll(/([A-Za-z_$][\w$]*)\s*:/g)].map((m) => m[1]));
  const strings = new Set([...text.matchAll(/(["'`])([^"'`\n]{0,60})\1/g)].map((m) => m[2]));
  const eventTypes = new Set([...text.matchAll(/(?:addEventListener|EventWatcher[^;]*?|waitForEvent|wait_for)\s*\(\s*[^,]*?,?\s*['"]([a-z]+)['"]/g)].map((m) => m[1]));
  for (const m of text.matchAll(/\[\s*((?:['"][a-z]+['"]\s*,?\s*)+)\]/g)) for (const s of m[1].matchAll(/['"]([a-z]+)['"]/g)) eventTypes.add(s[1]);
  const ctors = new Set([...text.matchAll(/new\s+([A-Z][\w$]*)\s*\(/g)].map((m) => m[1]));
  const mentioned = (owner) => idents.has(owner);

  for (const owner of owners) {
    if (mentioned(owner)) for (const i of typeItems(owner)) link(i, 'ref', t.rel);
    if (ctors.has(owner)) for (const i of byOwner.get(owner)) if (i.kind === 'constructor') link(i, 'ref', t.rel);
  }
  // Pass 1: interface members and enum values. Pass 2: dictionary fields, using the dictionaries
  // reachable from what pass 1 found (type flow) plus stats dictionaries selected by `type` strings.
  let reachable = new Set();
  for (const pass of [1, 2]) {
    if (pass === 2) {
      const start = new Set();
      for (const [id, l] of links) {
        if (!l.ref.has(t.rel) && !l.name.has(t.rel)) continue;
        const i = itemById.get(id);
        if (i.kind === 'dictionary') start.add(i.owner);
        else if (i.kind !== 'field') for (const d of dictsIn(i.signature)) start.add(d);
      }
      for (const [type, dicts] of Object.entries(STATS_TYPE_DICTS)) if (strings.has(type)) dicts.forEach((d) => start.add(d));
      reachable = reachDicts(start);
    }
    for (const [n, list] of byMember) {
      for (const i of list) {
        if ((i.kind === 'field') !== (pass === 2)) continue;
        let hit = false;
        switch (i.kind) {
          case 'attribute': case 'operation': case 'const':
            hit = dotRefs.has(n);
            break;
          case 'event-handler':
            hit = dotRefs.has(n) || (eventTypes.has(n.slice(2)) && mentioned(i.owner));
            break;
          case 'field':
            hit = keys.has(n) || dotRefs.has(n) || strings.has(n);
            break;
          case 'enum-value':
            hit = n.length >= 3 && strings.has(n);
            break;
          default:
            break;
        }
        if (!hit) continue;
        // Accept when: the owning type is named; or (fields) the dictionary is reachable by type flow.
        // Generic names (`.name`, `.state`, 'granted') need one of those. Otherwise the name must be
        // unique (across enums for values, across types for members), or — since dictionaries and
        // enums are never named in JS — the test lives in the item's own spec directory and the name
        // is unique within that spec.
        const ownDir = t.spec === i.spec && (i.kind === 'field' || i.kind === 'enum-value') && distinctiveInSpec(n, i);
        let ok;
        if (mentioned(i.owner) || (i.kind === 'field' && reachable.has(i.owner))) ok = true;
        else if (GENERIC.has(n)) ok = false;
        else ok = distinctive(n, i) || ownDir;
        if (ok) link(i, 'ref', t.rel);
      }
    }
  }

  // A type counts as touched when one of its members is.
  for (const [id, l] of links) {
    if (!l.ref.has(t.rel) && !l.name.has(t.rel)) continue;
    const i = itemById.get(id);
    if (TYPE_KINDS.has(i.kind)) continue;
    for (const ti of typeItems(i.owner)) if (!links.get(ti.id)?.name.has(t.rel)) link(ti, 'ref', t.rel);
  }

  meta.items = [...links].filter(([id, l]) => !before.has(id) && (l.name.has(t.rel) || l.ref.has(t.rel))).length;
  testMeta[t.rel] = meta;
}

// ---------------------------------------------------------------------------
// Output

const strength = (id) => {
  const l = links.get(id);
  if (!l) return 'none';
  if (l.name.size) return 'name';
  if (l.ref.size) return 'ref';
  return 'idl';
};

const out = {
  wpt: { repo: 'web-platform-tests/wpt', sha, dirs: Object.keys(WPT_DIRS) },
  tests: Object.fromEntries(Object.entries(testMeta).sort(([a], [b]) => a.localeCompare(b))),
  items: Object.fromEntries(
    items
      .filter((i) => links.has(i.id))
      .map((i) => {
        const l = links.get(i.id);
        const o = {};
        for (const k of ['name', 'ref', 'idl']) if (l[k].size) o[k] = [...l[k]].sort();
        return [i.id, o];
      }),
  ),
};
fs.writeFileSync(path.join(specDir, 'wpt-map.json'), `${JSON.stringify(out, null, 2)}\n`);

const pct = (n, d) => (d ? `${Math.round((100 * n) / d)}%` : '—');
const md = [
  '# web-platform-tests ↔ spec inventory',
  '',
  `Generated by \`tools/spec-inventory/map-wpt.mjs\` from WPT [\`${sha.slice(0, 12)}\`](https://github.com/web-platform-tests/wpt/tree/${sha}). Static analysis, nothing is executed.`,
  '',
  '- **name**: the test file is named after the item (`RTCPeerConnection-createOffer.html`).',
  '- **ref**: the test or a script it loads uses the item. This means the test touches the item, not that it checks its behaviour.',
  '- **idl**: covered only by an idlharness test (existence and shape).',
  '- **none**: no WPT test. krtc needs its own test here.',
  '',
  '## By spec',
  '',
  '| Spec | Tier | Items | name | ref | idl only | none | direct (name+ref) |',
  '|---|---|---:|---:|---:|---:|---:|---:|',
];
for (const [s, spec] of Object.entries(SPECS)) {
  const si = items.filter((i) => i.spec === s);
  if (!si.length) continue;
  const c = { name: 0, ref: 0, idl: 0, none: 0 };
  for (const i of si) c[strength(i.id)]++;
  md.push(`| \`${s}\` | ${spec.tier} | ${si.length} | ${c.name} | ${c.ref} | ${c.idl} | ${c.none} | ${pct(c.name + c.ref, si.length)} |`);
}

md.push('', '## By WPT directory', '', '| Directory | Test files | Subtests (approx.) | Protocol tests | Tentative | Files linked to no item |', '|---|---:|---:|---:|---:|---:|');
for (const d of Object.keys(WPT_DIRS)) {
  const ts = Object.entries(testMeta).filter(([, m]) => m.dir === d);
  if (!ts.length) continue;
  md.push(`| \`${d}\` | ${ts.length} | ${ts.reduce((a, [, m]) => a + m.subtests, 0)} | ${ts.filter(([, m]) => m.protocol).length} | ${ts.filter(([, m]) => m.tentative).length} | ${ts.filter(([rel, m]) => !m.idlharness && !Object.values(out.items).some((l) => l.name?.includes(rel) || l.ref?.includes(rel))).length} |`);
}

md.push('', '## Core items without a direct WPT test', '', 'These need krtc\'s own conformance tests, or a closer look: a test may cover them under a name the heuristic misses.', '');
for (const [s, spec] of Object.entries(SPECS)) {
  if (spec.tier !== 'core') continue;
  const gaps = items.filter((i) => i.spec === s && ['idl', 'none'].includes(strength(i.id)));
  if (!gaps.length) continue;
  md.push(`<details><summary><code>${s}</code>: ${gaps.length}</summary>`, '');
  for (const i of gaps) md.push(`- \`${i.id}\`${strength(i.id) === 'idl' ? ' _(idl only)_' : ''}`);
  md.push('', '</details>', '');
}

const unmapped = Object.entries(testMeta).filter(([rel, m]) => !m.idlharness && !Object.values(out.items).some((l) => l.name?.includes(rel) || l.ref?.includes(rel)));
md.push('## Test files linked to no item', '', unmapped.length ? 'Mostly protocol (IETF) behaviour or crash/GC tests. Track them separately when porting.' : 'None.', '');
for (const [rel] of unmapped) md.push(`- [\`${rel}\`](https://github.com/web-platform-tests/wpt/blob/${sha}/${rel})`);
md.push('');
fs.writeFileSync(path.join(specDir, 'WPT.md'), md.join('\n'));

const c = { name: 0, ref: 0, idl: 0, none: 0 };
const core = items.filter((i) => SPECS[i.spec].tier === 'core');
for (const i of core) c[strength(i.id)]++;
console.log(`WPT ${sha.slice(0, 12)}: ${tests.length} test files mapped onto ${items.length} items`);
console.log(`  core (${core.length}): name ${c.name}, ref ${c.ref}, idl-only ${c.idl}, none ${c.none}; unmapped test files ${unmapped.length}`);
