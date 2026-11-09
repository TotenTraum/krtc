// Builds the krtc spec inventory from @webref/idl.
//
// Outputs (in <repo>/spec):
//   inventory.json      — every in-scope IDL item, machine-readable (regenerated each run)
//   CHECKLIST.md        — the same, human-readable (regenerated each run)
//   spec-coverage.yaml  — per-item, per-platform status + tests; hand-edited, MERGED on regeneration:
//                         existing status/tests/notes are kept, new items are added as `planned`,
//                         items gone from the IDL are moved to `removed:` (never silently dropped).
//   COVERAGE.md         — summary computed from spec-coverage.yaml
//
// Usage: npm run generate            (regenerate everything)
//        npm run generate -- --check (fail if spec-coverage.yaml is out of sync; for CI)

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import * as WebIDL2 from 'webidl2';
import YAML from 'yaml';
import { SPECS, PLATFORMS, STATUSES, DOM_BOUND_TARGETS } from './scope.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const outDir = path.join(repoRoot, 'spec');
const require = createRequire(import.meta.url);
const idlDir = path.dirname(require.resolve('@webref/idl/package.json'));
const webrefVersion = JSON.parse(fs.readFileSync(path.join(idlDir, 'package.json'), 'utf8')).version;
const checkOnly = process.argv.includes('--check');

// ---------------------------------------------------------------------------
// IDL → inventory items

function typeStr(t) {
  if (!t) return 'undefined';
  let s;
  if (t.union) s = `(${t.idlType.map(typeStr).join(' or ')})`;
  else if (t.generic) s = `${t.generic}<${t.idlType.map(typeStr).join(', ')}>`;
  else s = typeof t.idlType === 'string' ? t.idlType : typeStr(t.idlType);
  return s + (t.nullable ? '?' : '');
}

function argsStr(args = []) {
  return args
    .map((a) => `${a.optional ? 'optional ' : ''}${typeStr(a.idlType)}${a.variadic ? '...' : ''} ${a.name}`)
    .join(', ');
}

function defaultStr(d) {
  switch (d.type) {
    case 'string': return `"${d.value}"`;
    case 'sequence': return '[]';
    case 'dictionary': return '{}';
    case 'null': return 'null';
    default: return String(d.value);
  }
}

function extAttrNames(node) {
  return (node.extAttrs ?? []).map((e) => e.name);
}

function exposure(node) {
  const ea = (node.extAttrs ?? []).find((e) => e.name === 'Exposed');
  if (!ea || !ea.rhs) return [];
  const v = ea.rhs.value;
  return Array.isArray(v) ? v.map((x) => x.value) : [v];
}

const DEF_KIND = {
  'interface': 'interface',
  'interface mixin': 'mixin',
  'callback interface': 'callback-interface',
  'callback': 'callback',
  'dictionary': 'dictionary',
  'enum': 'enum',
  'typedef': 'typedef',
  'namespace': 'namespace',
};

function collect(shortname, spec, ast) {
  const items = [];
  const push = (it) => items.push({ spec: shortname, tier: spec.tier, ...it });

  for (const def of ast) {
    if (def.type === 'includes') {
      push({
        id: `${shortname}:${def.target} includes ${def.includes}`,
        kind: 'includes',
        owner: def.target,
        name: def.includes,
        signature: `${def.target} includes ${def.includes};`,
      });
      continue;
    }
    const kind = DEF_KIND[def.type];
    if (!kind) continue;
    const owner = def.name;
    const domBound = DOM_BOUND_TARGETS.has(owner);
    const exposed = exposure(def);

    // The definition itself (a partial only counts as an extension point, not a new type).
    if (!def.partial) {
      push({
        id: `${shortname}:${owner}`,
        kind,
        owner,
        name: owner,
        signature:
          kind === 'typedef' ? `typedef ${typeStr(def.idlType)} ${owner};`
          : kind === 'callback' ? `callback ${owner} = ${typeStr(def.idlType)} (${argsStr(def.arguments)});`
          : `${def.type} ${owner}${def.inheritance ? ` : ${def.inheritance}` : ''}`,
        exposed,
        extAttrs: extAttrNames(def),
      });
    }

    if (kind === 'enum') {
      for (const v of def.values) {
        push({ id: `${shortname}:${owner}."${v.value}"`, kind: 'enum-value', owner, name: v.value, signature: `"${v.value}"` });
      }
      continue;
    }

    // Overloads share one id; keep a count so the checklist shows them.
    const seen = new Map();
    for (const m of def.members ?? []) {
      let mkind, name, signature;
      switch (m.type) {
        case 'attribute': {
          const t = typeStr(m.idlType);
          mkind = t === 'EventHandler' ? 'event-handler' : 'attribute';
          name = m.name;
          signature = `${m.special ? m.special + ' ' : ''}${m.readonly ? 'readonly ' : ''}attribute ${t} ${m.name}`;
          break;
        }
        case 'operation':
          mkind = 'operation';
          name = m.name ? `${m.name}()` : `[${m.special}]()`;
          signature = `${m.special ? m.special + ' ' : ''}${typeStr(m.idlType)} ${m.name ?? ''}(${argsStr(m.arguments)})`;
          break;
        case 'constructor':
          mkind = 'constructor';
          name = 'constructor()';
          signature = `constructor(${argsStr(m.arguments)})`;
          break;
        case 'const':
          mkind = 'const';
          name = m.name;
          signature = `const ${typeStr(m.idlType)} ${m.name}`;
          break;
        case 'field':
          mkind = 'field';
          name = m.name;
          signature = `${m.required ? 'required ' : ''}${typeStr(m.idlType)} ${m.name}${m.default ? ' = ' + defaultStr(m.default) : ''}`;
          break;
        case 'iterable':
        case 'maplike':
        case 'setlike':
          mkind = m.type;
          name = `[${m.type}]`;
          signature = `${m.readonly ? 'readonly ' : ''}${m.type}<${m.idlType.map(typeStr).join(', ')}>`;
          break;
        default:
          continue;
      }
      const id = `${shortname}:${owner}.${name}`;
      if (seen.has(id)) {
        const prev = seen.get(id);
        prev.overloads = (prev.overloads ?? 1) + 1;
        prev.signatures = [...(prev.signatures ?? [prev.signature]), signature];
        continue;
      }
      const item = {
        id,
        kind: mkind,
        owner,
        name,
        signature,
        static: m.special === 'static' || undefined,
        partialOf: def.partial ? owner : undefined,
        domBound: domBound || undefined,
        exposed: exposure(m).length ? exposure(m) : exposed.length ? exposed : undefined,
        extAttrs: extAttrNames(m).length ? extAttrNames(m) : undefined,
      };
      seen.set(id, item);
      push(item);
    }
  }
  return items;
}

function buildInventory() {
  const available = new Set(fs.readdirSync(idlDir).filter((f) => f.endsWith('.idl')).map((f) => f.slice(0, -4)));
  const missing = Object.keys(SPECS).filter((s) => !available.has(s));
  if (missing.length) {
    console.warn(`warning: not present in @webref/idl ${webrefVersion}: ${missing.join(', ')}`);
  }
  const items = [];
  for (const [shortname, spec] of Object.entries(SPECS)) {
    if (!available.has(shortname)) continue;
    const text = fs.readFileSync(path.join(idlDir, `${shortname}.idl`), 'utf8');
    items.push(...collect(shortname, spec, WebIDL2.parse(text)));
  }
  const dup = items.map((i) => i.id).filter((id, i, a) => a.indexOf(id) !== i);
  if (dup.length) throw new Error(`duplicate ids: ${[...new Set(dup)].join(', ')}`);
  return items;
}

// ---------------------------------------------------------------------------
// spec-coverage.yaml merge

function defaultEntry(item) {
  return {
    kind: item.kind,
    tier: item.tier,
    status: item.tier === 'excluded' ? { default: 'na' } : { default: 'planned' },
    tests: [],
  };
}

function validateEntry(id, e, errors) {
  for (const [p, s] of Object.entries(e.status ?? {})) {
    if (p !== 'default' && !PLATFORMS.includes(p)) errors.push(`${id}: unknown platform "${p}"`);
    if (!STATUSES.includes(s)) errors.push(`${id}: unknown status "${s}" for ${p}`);
  }
  if (!e.status?.default) errors.push(`${id}: status.default is required`);
}

function mergeCoverage(items, existing) {
  const oldItems = existing?.items ?? {};
  const removed = { ...(existing?.removed ?? {}) };
  const merged = {};
  let added = 0;
  for (const it of items) {
    const prev = oldItems[it.id] ?? removed[it.id];
    if (removed[it.id]) delete removed[it.id];
    if (prev) {
      // kind/tier always follow the IDL and scope.mjs; status/tests/notes are owned by humans.
      merged[it.id] = { ...prev, kind: it.kind, tier: it.tier };
    } else {
      merged[it.id] = defaultEntry(it);
      added++;
    }
  }
  let dropped = 0;
  for (const [id, e] of Object.entries(oldItems)) {
    if (!merged[id]) {
      removed[id] = e;
      dropped++;
    }
  }
  return { merged, removed, added, dropped };
}

function effectiveStatus(entry, platform) {
  return entry.status?.[platform] ?? entry.status?.default ?? 'planned';
}

// ---------------------------------------------------------------------------
// Renderers

function renderChecklist(items) {
  const lines = [
    '# WebRTC spec checklist',
    '',
    `Generated by \`tools/spec-inventory\` from \`@webref/idl@${webrefVersion}\`. Do not edit — edit \`spec-coverage.yaml\`.`,
    '',
  ];
  const byTier = { core: [], extended: [], excluded: [] };
  for (const [s, spec] of Object.entries(SPECS)) byTier[spec.tier].push(s);
  for (const tier of ['core', 'extended', 'excluded']) {
    lines.push(`## ${tier[0].toUpperCase()}${tier.slice(1)}`, '');
    for (const s of byTier[tier]) {
      const spec = SPECS[s];
      const specItems = items.filter((i) => i.spec === s);
      if (!specItems.length) continue;
      lines.push(`### [${spec.title}](${spec.url}) — \`${s}\` (${specItems.length})`, '');
      if (spec.reason) lines.push(`> Excluded: ${spec.reason}`, '');
      const owners = [...new Set(specItems.map((i) => i.owner))];
      for (const o of owners) {
        const ofOwner = specItems.filter((i) => i.owner === o);
        const head = ofOwner.find((i) => i.name === o && i.kind !== 'includes');
        const title = head ? `\`${head.signature}\`` : `partial \`${o}\``;
        const flags = ofOwner.some((i) => i.domBound) ? ' — ⚠ DOM-bound, needs native design' : '';
        lines.push(`- [ ] ${title}${flags}`);
        for (const i of ofOwner) {
          if (i === head) continue;
          const extra = [i.static && 'static', i.overloads && `${i.overloads} overloads`, i.exposed && !i.exposed.includes('Window') && `exposed: ${i.exposed.join(', ')}`]
            .filter(Boolean)
            .join(', ');
          lines.push(`  - [ ] \`${i.signature}\` _(${i.kind}${extra ? '; ' + extra : ''})_`);
        }
      }
      lines.push('');
    }
  }
  return lines.join('\n');
}

function renderCoverage(items, coverage) {
  const wptPath = path.join(outDir, 'wpt-map.json');
  const wpt = fs.existsSync(wptPath) ? JSON.parse(fs.readFileSync(wptPath, 'utf8')) : null;
  const pct = (n, d) => (d ? `${Math.round((100 * n) / d)}%` : '—');
  const lines = [
    '# WebRTC spec coverage',
    '',
    `Computed from \`spec-coverage.yaml\` (IDL: \`@webref/idl@${webrefVersion}\`). Counts \`full\` as covered, \`partial\` as half; \`na\` items are left out of the denominator.`,
    `*WPT direct*: share of items with a web-platform-tests file named after or touching them (see [WPT.md](WPT.md)${wpt ? `, WPT \`${wpt.wpt.sha.slice(0, 12)}\`` : ', not mapped yet: run `npm run wpt:map`'}).`,
    '',
    `| Spec | Tier | Items | WPT direct | ${PLATFORMS.join(' | ')} |`,
    `|---|---|---:|---:|${PLATFORMS.map(() => '---:').join('|')}|`,
  ];
  const totals = Object.fromEntries(PLATFORMS.map((p) => [p, { score: 0, denom: 0 }]));
  for (const [s, spec] of Object.entries(SPECS)) {
    const specItems = items.filter((i) => i.spec === s);
    if (!specItems.length) continue;
    const cells = PLATFORMS.map((p) => {
      let score = 0, denom = 0;
      for (const it of specItems) {
        const st = effectiveStatus(coverage[it.id], p);
        if (st === 'na') continue;
        denom++;
        score += st === 'full' ? 1 : st === 'partial' ? 0.5 : 0;
      }
      if (spec.tier === 'core') { totals[p].score += score; totals[p].denom += denom; }
      return pct(score, denom);
    });
    const wptDirect = wpt ? pct(specItems.filter((i) => wpt.items[i.id]?.name || wpt.items[i.id]?.ref).length, specItems.length) : '—';
    lines.push(`| \`${s}\` | ${spec.tier} | ${specItems.length} | ${wptDirect} | ${cells.join(' | ')} |`);
  }
  lines.push(`| **core total** | | | | ${PLATFORMS.map((p) => `**${pct(totals[p].score, totals[p].denom)}**`).join(' | ')} |`, '');

  const kinds = {};
  for (const it of items) kinds[it.kind] = (kinds[it.kind] ?? 0) + 1;
  lines.push('## Items by kind', '', '| Kind | Count |', '|---|---:|');
  for (const [k, n] of Object.entries(kinds).sort((a, b) => b[1] - a[1])) lines.push(`| ${k} | ${n} |`);
  const untested = items.filter((i) => i.tier === 'core' && !(coverage[i.id].tests ?? []).length).length;
  lines.push('', `Core items without a linked test: **${untested}**.`, '');
  return lines.join('\n');
}

const COVERAGE_HEADER = `# krtc spec coverage matrix — hand-edited, merged by tools/spec-inventory.
#
# items.<id>.status: default + per-platform overrides.
#   platforms: ${PLATFORMS.join(', ')}
#   statuses:  planned | partial | full | na
# items.<id>.tests: ids/paths of tests proving the item (krtc-conformance, ported WPT, interop).
# items.<id>.notes: free text (e.g. why something is "na" on a platform).
# kind/tier are rewritten from the IDL and scope.mjs on every run; everything else is yours.
# removed: entries whose IDL item disappeared upstream — review and delete by hand.
`;

// ---------------------------------------------------------------------------

function main() {
  const items = buildInventory();
  const covPath = path.join(outDir, 'spec-coverage.yaml');
  const existing = fs.existsSync(covPath) ? YAML.parse(fs.readFileSync(covPath, 'utf8')) : null;
  const { merged, removed, added, dropped } = mergeCoverage(items, existing);

  const errors = [];
  for (const [id, e] of Object.entries(merged)) validateEntry(id, e, errors);
  if (errors.length) {
    console.error(errors.join('\n'));
    process.exit(1);
  }

  if (checkOnly) {
    if (added || dropped) {
      console.error(`spec-coverage.yaml is out of sync: ${added} new, ${dropped} removed items. Run \`npm run generate\`.`);
      process.exit(1);
    }
    console.log(`spec-coverage.yaml in sync (${items.length} items).`);
    return;
  }

  fs.mkdirSync(outDir, { recursive: true });
  const doc = { webref: webrefVersion, items: merged };
  if (Object.keys(removed).length) doc.removed = removed;
  fs.writeFileSync(covPath, COVERAGE_HEADER + YAML.stringify(doc, { lineWidth: 0 }));
  fs.writeFileSync(
    path.join(outDir, 'inventory.json'),
    JSON.stringify({ webref: webrefVersion, specs: SPECS, items }, null, 2) + '\n',
  );
  fs.writeFileSync(path.join(outDir, 'CHECKLIST.md'), renderChecklist(items));
  fs.writeFileSync(path.join(outDir, 'COVERAGE.md'), renderCoverage(items, merged));

  const bySpec = {};
  for (const it of items) bySpec[it.spec] = (bySpec[it.spec] ?? 0) + 1;
  console.log(`@webref/idl ${webrefVersion}: ${items.length} items (${added} new, ${dropped} removed)`);
  for (const [s, n] of Object.entries(bySpec)) console.log(`  ${SPECS[s].tier.padEnd(9)} ${s.padEnd(30)} ${n}`);
}

main();
