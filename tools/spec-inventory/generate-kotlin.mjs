// Generates the raw, W3C-shaped Kotlin API skeleton (module krtc-idl) from @webref/idl.
//
// The output is a faithful 1:1 projection of the in-scope WebIDL (core + extended tiers) onto Kotlin,
// following ADR-4 in docs/PLAN.md. It is regenerated on every run — never edit it by hand. The
// idiomatic public API (stage 5) is designed on top of / next to it.
//
// Usage: npm run generate:kotlin             (rewrite krtc-idl/src/commonMain/generated)
//        npm run generate:kotlin -- --check  (fail if the checked-in output is stale; for CI)
//
// Mapping (IDL → Kotlin):
//   interface / mixin        → interface (inheritance + `includes` → supertypes; partials merged in)
//   constructor / static     → companion object delegating to KrtcFactory (implemented per platform)
//   attribute                → val / var;  EventHandler attribute → Flow<EventType> (type from @webref/events)
//   operation                → fun;  returning Promise<T> → suspend fun returning T
//   maplike / setlike / iter → Map / MutableMap / Set / Iterable supertype
//   dictionary               → data class (leaf) / open class (has children); defaults preserved
//   enum                     → enum class with the IDL string as `value`
//   typedef                  → typealias;  union (typedef'd or inline) → sealed interface of value classes
//   callback                 → function typealias;  callback interface → fun interface
//   Promise attribute        → Deferred<T>
//   numbers                  → Int (≤ 32-bit signed fits), Long (unsigned long / long long), Double, Float
//   buffers                  → ByteArray (typed arrays → matching primitive arrays)
//   types outside scope      → placeholder interfaces in External.kt (with the defining spec named)

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import * as WebIDL2 from 'webidl2';
import { SPECS, KOTLIN_PACKAGE, DOM_BOUND_TARGETS } from './scope.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const outDir = path.join(repoRoot, 'krtc-idl/src/commonMain/generated', ...KOTLIN_PACKAGE.split('.'));
const require = createRequire(import.meta.url);
const idlDir = path.dirname(require.resolve('@webref/idl/package.json'));
const webrefVersion = JSON.parse(fs.readFileSync(path.join(idlDir, 'package.json'), 'utf8')).version;
const events = JSON.parse(fs.readFileSync(require.resolve('@webref/events/events.json'), 'utf8'));
const checkOnly = process.argv.includes('--check');

const EXTENDED_ANNOTATION = 'KrtcExtendedApi';

// ---------------------------------------------------------------------------
// Load & index

const inScope = Object.entries(SPECS).filter(([s, spec]) => spec.tier !== 'excluded' && fs.existsSync(path.join(idlDir, `${s}.idl`)));
const asts = Object.fromEntries(inScope.map(([s]) => [s, WebIDL2.parse(fs.readFileSync(path.join(idlDir, `${s}.idl`), 'utf8'))]));

// Where every non-partial definition across all of webref lives (to label placeholders).
const webrefIndex = new Map();
for (const f of fs.readdirSync(idlDir).filter((f) => f.endsWith('.idl'))) {
  try {
    for (const d of WebIDL2.parse(fs.readFileSync(path.join(idlDir, f), 'utf8'))) {
      if (d.name && !d.partial && !webrefIndex.has(d.name)) webrefIndex.set(d.name, { spec: f.slice(0, -4), def: d });
    }
  } catch { /* some upstream files may not parse with this webidl2 version; irrelevant for labels */ }
}

const KIND = {
  'interface': 'interface', 'interface mixin': 'mixin', 'callback interface': 'callback-interface',
  'callback': 'callback', 'dictionary': 'dictionary', 'enum': 'enum', 'typedef': 'typedef', 'namespace': 'namespace',
};

// name → { name, kind, spec, def, parts: [{spec, def}], mixins: [{name, spec}] }
const reg = new Map();
const entry = (name) => {
  if (!reg.has(name)) reg.set(name, { name, kind: null, spec: null, def: null, parts: [], mixins: [] });
  return reg.get(name);
};
for (const [spec, ast] of Object.entries(asts)) {
  for (const def of ast) {
    if (def.type === 'includes') { entry(def.target).mixins.push({ name: def.includes, spec }); continue; }
    if (!KIND[def.type]) continue;
    const e = entry(def.name);
    if (def.partial) { e.parts.push({ spec, def }); e.partialKind ??= KIND[def.type]; }
    else Object.assign(e, { kind: KIND[def.type], spec, def });
  }
}
// Partial-only entries extend something defined outside scope (Navigator, HTMLMediaElement, ...).
for (const e of reg.values()) {
  if (!e.def) { e.kind = e.partialKind; e.external = true; e.spec = e.parts[0].spec; }
}

const BASE_TYPES = new Set(['Event', 'EventTarget', 'EventInit', 'DOMException']);
const EVENT_INIT_FIELDS = ['bubbles', 'cancelable', 'composed'].map((n) => ({ name: n, type: 'Boolean', def: 'false', inherited: true }));

const typedefs = new Map([...reg.values()].filter((e) => e.kind === 'typedef').map((e) => [e.name, e.def.idlType]));
const enumNames = new Set([...reg.values()].filter((e) => e.kind === 'enum').map((e) => e.name));
const dictParents = new Set();
for (const e of reg.values()) if (e.kind === 'dictionary' && e.def?.inheritance) dictParents.add(e.def.inheritance);

const externals = new Map(); // name → 'interface' | 'event' | 'dictionary-parent'
const unions = new Map(); // name → [{alt, type}]
const factory = []; // lines in KrtcFactory

// ---------------------------------------------------------------------------
// Names & literals

const KEYWORDS = new Set('as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while'.split(' '));
const id = (n) => (KEYWORDS.has(n) || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(n) ? `\`${n}\`` : n);
const kstr = (s) => JSON.stringify(s).replace(/\$/g, '\\$');

function enumConst(v) {
  let s = v.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '').toUpperCase();
  if (!s) return 'EMPTY';
  return /^[0-9]/.test(s) ? `_${s}` : s;
}
function enumConsts(values) {
  const used = new Map();
  return values.map((v) => {
    let c = enumConst(v);
    while (used.has(c)) c += '_';
    used.set(c, v);
    return c;
  });
}

function anchor(specName, ...parts) {
  return `${SPECS[specName].url}#dom-${parts.map((p) => String(p).toLowerCase().replace(/[^a-z0-9-]/g, '')).join('-')}`;
}
function doc(specName, itemId, href, extra = []) {
  const lines = [`[${itemId}](${href})`, ...extra];
  return lines.length === 1 ? `/** ${lines[0]} */` : ['/**', ...lines.map((l) => ` * ${l}`), ' */'].join('\n');
}
const extAnno = (specName) => (SPECS[specName].tier === 'extended' ? `@${EXTENDED_ANNOTATION}\n` : '');

// ---------------------------------------------------------------------------
// Types

const PRIM = {
  boolean: 'Boolean', byte: 'Byte', octet: 'Int', short: 'Int', 'unsigned short': 'Int', long: 'Int',
  'unsigned long': 'Long', 'long long': 'Long', 'unsigned long long': 'Long', bigint: 'Long',
  float: 'Float', 'unrestricted float': 'Float', double: 'Double', 'unrestricted double': 'Double',
  DOMString: 'String', USVString: 'String', ByteString: 'String',
  undefined: 'Unit', any: 'Any?', object: 'Any', symbol: 'Any',
  DOMHighResTimeStamp: 'Double', EpochTimeStamp: 'Long',
  ArrayBuffer: 'ByteArray', SharedArrayBuffer: 'ByteArray', ArrayBufferView: 'ByteArray', BufferSource: 'ByteArray',
  AllowSharedBufferSource: 'ByteArray', DataView: 'ByteArray', Int8Array: 'ByteArray', Uint8Array: 'ByteArray',
  Uint8ClampedArray: 'ByteArray', Int16Array: 'ShortArray', Uint16Array: 'ShortArray', Int32Array: 'IntArray',
  Uint32Array: 'IntArray', BigInt64Array: 'LongArray', BigUint64Array: 'LongArray', Float32Array: 'FloatArray',
  Float64Array: 'DoubleArray',
};

const nullable = (k) => (k.endsWith('?') ? k : `${k}?`);

function typeIdent(k) {
  let m;
  k = k.replace(/\?$/, '');
  if ((m = /^List<(.*)>$/.exec(k))) return `${typeIdent(m[1])}List`;
  if ((m = /^Map<([^,]*), (.*)>$/.exec(k))) return `${typeIdent(m[1])}To${typeIdent(m[2])}Map`;
  if ((m = /^Deferred<(.*)>$/.exec(k))) return `${typeIdent(m[1])}Deferred`;
  return k.replace(/[^A-Za-z0-9]/g, '');
}

function flattenUnion(members) {
  return members.flatMap((m) => (m.union ? flattenUnion(m.idlType) : [m]));
}

// Returns the Kotlin type; registers unions/externals as a side effect.
function kt(t, unionName) {
  let s;
  if (t.union) {
    s = unionType(flattenUnion(t.idlType), unionName);
  } else if (t.generic) {
    const a = t.idlType;
    switch (t.generic) {
      case 'Promise': s = `Deferred<${kt(a[0])}>`; break;
      case 'sequence': case 'FrozenArray': case 'ObservableArray': s = `List<${kt(a[0])}>`; break;
      case 'record': s = `Map<${kt(a[0])}, ${kt(a[1])}>`; break;
      default: throw new Error(`unknown generic ${t.generic}`);
    }
  } else {
    const n = typeof t.idlType === 'string' ? t.idlType : kt(t.idlType);
    if (PRIM[n]) s = PRIM[n];
    else if (BASE_TYPES.has(n) || reg.has(n)) s = n;
    else {
      if (!externals.has(n)) externals.set(n, /Event$/.test(n) ? 'event' : 'interface');
      s = n;
    }
  }
  return t.nullable ? nullable(s) : s;
}

function unionType(members, name) {
  const alts = [];
  for (const m of members) {
    const type = kt(m);
    if (!alts.some((a) => a.type === type)) alts.push({ type, alt: `Of${typeIdent(type)}` });
  }
  if (alts.length === 1 && !name) return alts[0].type;
  name ??= alts.map((a) => typeIdent(a.type)).join('Or');
  if (!unions.has(name)) unions.set(name, alts);
  return name;
}

// Unwraps a top-level Promise for operations (→ suspend fun).
function returnType(t) {
  if (t.generic === 'Promise') return { suspend: true, type: kt(t.idlType[0]) };
  return { suspend: false, type: kt(t) };
}

function resolveTypedef(t) {
  while (!t.union && !t.generic && typedefs.has(t.idlType)) t = typedefs.get(t.idlType);
  return t;
}

function numberLiteral(v, base) {
  if (base === 'Long') return `${v}L`;
  if (base === 'Double') return /[.eE]/.test(v) ? v : `${v}.0`;
  if (base === 'Float') return `${v}f`;
  return v;
}

function defaultExpr(d, idlType, ktType) {
  const base = ktType.replace(/\?$/, '');
  const t = resolveTypedef(idlType);
  // A boolean/number default for a union-typed slot wraps the matching alternative.
  if ((d.type === 'boolean' || d.type === 'number') && unions.has(base)) {
    const want = d.type === 'boolean' ? ['Boolean'] : ['Int', 'Long', 'Double', 'Float', 'Byte'];
    const alt = unions.get(base).find((a) => want.includes(a.type));
    if (!alt) throw new Error(`no ${d.type} alternative in ${base}`);
    return `${base}.${alt.alt}(${d.type === 'boolean' ? String(d.value) : numberLiteral(d.value, alt.type)})`;
  }
  switch (d.type) {
    case 'null': return 'null';
    case 'boolean': return String(d.value);
    case 'number': return numberLiteral(d.value, base);
    case 'Infinity': return `${base}.${d.negative ? 'NEGATIVE' : 'POSITIVE'}_INFINITY`;
    case 'NaN': return `${base}.NaN`;
    case 'sequence': return 'emptyList()';
    case 'dictionary': return `${base}()`;
    case 'string': {
      if (!t.union && enumNames.has(t.idlType)) {
        const en = reg.get(t.idlType);
        const values = en.def.values.map((v) => v.value);
        return `${t.idlType}.${enumConsts(values)[values.indexOf(d.value)]}`;
      }
      if (base === 'String') return kstr(d.value);
      if (unions.has(base)) {
        const alts = unions.get(base);
        const str = alts.find((a) => a.type === 'String');
        if (str) return `${base}.${str.alt}(${kstr(d.value)})`;
        for (const a of alts) {
          if (enumNames.has(a.type)) {
            const values = reg.get(a.type).def.values.map((v) => v.value);
            const i = values.indexOf(d.value);
            if (i >= 0) return `${base}.${a.alt}(${a.type}.${enumConsts(values)[i]})`;
          }
        }
      }
      throw new Error(`cannot express string default "${d.value}" for ${ktType}`);
    }
    default: throw new Error(`unknown default ${JSON.stringify(d)}`);
  }
}

// Arguments → Kotlin parameter list. `withDefaults` adds IDL defaults (not allowed in KrtcFactory).
function params(args = [], withDefaults = true) {
  return args.map((a) => {
    let type = kt(a.idlType);
    if (a.variadic) return `vararg ${id(a.name)}: ${type}`;
    let def = '';
    if (a.optional) {
      if (a.default) def = defaultExpr(a.default, a.idlType, type);
      else if (reg.get(resolveTypedef(a.idlType).idlType)?.kind === 'dictionary' && !a.idlType.nullable) def = `${type}()`;
      else { type = nullable(type); def = 'null'; }
    }
    return `${id(a.name)}: ${type}${withDefaults && def ? ` = ${def}` : ''}`;
  });
}
const argNames = (args = []) => args.map((a) => (a.variadic ? `*${id(a.name)}` : id(a.name))).join(', ');
const erasedKey = (args = []) => args.map((a) => kt(a.idlType).replace(/<.*>/, '').replace(/\?$/, '')).join(',');

function isCallbackType(t) {
  const n = resolveTypedef(t).idlType;
  return typeof n === 'string' && (reg.get(n)?.kind === 'callback' || webrefIndex.get(n)?.def.type === 'callback');
}

function eventType(owner, handler) {
  const type = handler.replace(/^on/, '');
  const hit = events.find((e) => e.type === type && (e.targets ?? []).some((t) => t.target === owner))
    ?? events.find((e) => e.type === type && /^(RTC|Media|Capture)/.test((e.targets ?? [])[0]?.target ?? ''));
  const name = hit?.interface ?? 'Event';
  return kt({ idlType: name, nullable: false });
}

// ---------------------------------------------------------------------------
// Emitters — each returns a Kotlin source fragment.

// Instance member keys ("op:name(erased args)" / "attr:name") declared by any supertype of e.
function inheritedMembers(e, acc = new Set(), self = true) {
  if (!self) {
    for (const def of [e.def, ...e.parts.map((p) => p.def)].filter(Boolean)) {
      for (const m of def.members ?? []) {
        if (m.special === 'static') continue;
        if (m.type === 'operation' && m.name) acc.add(`op:${m.name}(${erasedKey(m.arguments)})`);
        if (m.type === 'attribute') acc.add(`attr:${m.name}`);
      }
    }
  }
  const supers = [e.def?.inheritance, ...e.mixins.map((m) => m.name)].filter((n) => n && reg.has(n));
  for (const s of supers) inheritedMembers(reg.get(s), acc, false);
  return acc;
}

function emitInterface(e) {
  const defs = [...(e.def ? [{ spec: e.spec, def: e.def }] : []), ...e.parts];
  const supers = [];
  if (e.def?.inheritance) supers.push(kt({ idlType: e.def.inheritance }));
  for (const m of e.mixins) if (reg.has(m.name)) supers.push(m.name);

  const body = [];
  const companion = [];
  const seen = new Set();
  const inherited = inheritedMembers(e);
  const opNames = new Map();
  for (const { def } of defs) for (const m of def.members ?? []) if (m.type === 'operation' && m.name) opNames.set(m.name, (opNames.get(m.name) ?? 0) + 1);
  const interfaceKind = e.kind === 'callback-interface' ? 'fun interface' : 'interface';

  for (const { spec, def } of defs) {
    const anno = extAnno(spec);
    for (const m of def.members ?? []) {
      switch (m.type) {
        case 'iterable': case 'maplike': case 'setlike': {
          const ts = m.idlType.map((t) => kt(t));
          if (m.type === 'maplike') supers.push(`${m.readonly ? 'Map' : 'MutableMap'}<${ts[0]}, ${ts[1]}>`);
          else if (m.type === 'setlike') supers.push(`${m.readonly ? 'Set' : 'MutableSet'}<${ts[0]}>`);
          else supers.push(ts.length === 2 ? `Iterable<Pair<${ts[0]}, ${ts[1]}>>` : `Iterable<${ts[0]}>`);
          break;
        }
        case 'const': {
          const t = kt(m.idlType);
          const v = defaultExpr(m.value, m.idlType, t);
          companion.push(`${doc(spec, `${spec}:${e.name}.${m.name}`, anchor(spec, e.name, m.name))}\n${anno}public const val ${m.name}: ${t} = ${v}`);
          break;
        }
        case 'constructor': {
          const key = `ctor(${erasedKey(m.arguments)})`;
          if (seen.has(key)) break;
          seen.add(key);
          factory.push(`${anno}public fun new${e.name}(${params(m.arguments, false).join(', ')}): ${e.name}`);
          companion.push(`${doc(spec, `${spec}:${e.name}.constructor()`, anchor(spec, e.name, 'constructor'))}\n${anno}public operator fun invoke(${params(m.arguments).join(', ')}): ${e.name} =\n    Krtc.factory.new${e.name}(${argNames(m.arguments)})`);
          break;
        }
        case 'attribute': {
          const itemId = `${spec}:${e.name}.${m.name}`;
          const d = doc(spec, itemId, anchor(spec, e.name, m.name));
          const isHandler = !m.idlType.union && !m.idlType.generic && m.idlType.idlType === 'EventHandler';
          if (isHandler) {
            const ev = eventType(e.name, m.name);
            body.push(`${doc(spec, itemId, anchor(spec, e.name, m.name), [`Flow of \`${m.name.slice(2)}\` events.`])}\n${anno}public val ${id(m.name)}: Flow<${ev}>`);
            break;
          }
          const t = kt(m.idlType);
          if (m.special === 'static') {
            factory.push(`${anno}public ${m.readonly ? 'val' : 'var'} ${e.name}_${m.name}: ${t}`);
            companion.push(`${d}\n${anno}public ${m.readonly ? 'val' : 'var'} ${id(m.name)}: ${t}\n    get() = Krtc.factory.${e.name}_${m.name}${m.readonly ? '' : `\n    set(value) { Krtc.factory.${e.name}_${m.name} = value }`}`);
          } else {
            const mod = inherited.has(`attr:${m.name}`) ? 'override' : 'public';
            body.push(`${d}\n${anno}${mod} ${m.readonly ? 'val' : 'var'} ${id(m.name)}: ${t}`);
          }
          break;
        }
        case 'operation': {
          if (!m.name) {
            if (m.special === 'getter') body.push(`${anno}public operator fun get(${params(m.arguments).join(', ')}): ${kt(m.idlType)}`);
            break; // unnamed stringifier/setter/deleter: no Kotlin surface needed
          }
          const key = `${m.special === 'static' ? 's:' : ''}${m.name}(${erasedKey(m.arguments)})`;
          if (seen.has(key)) break;
          seen.add(key);
          const { suspend, type } = returnType(m.idlType);
          const ret = type === 'Unit' ? '' : `: ${type}`;
          let d = doc(spec, `${spec}:${e.name}.${m.name}()`, anchor(spec, e.name, m.name));
          const sus = suspend ? 'suspend ' : '';
          const legacy = opNames.get(m.name) > 1 && (m.arguments ?? []).some((a) => isCallbackType(a.idlType));
          if (legacy) d += `\n@Deprecated("Legacy callback-based overload; use the suspend overload.")`;
          if (m.special === 'static') {
            factory.push(`${anno}public ${sus}fun ${e.name}_${m.name}(${params(m.arguments, false).join(', ')})${ret}`);
            companion.push(`${d}\n${anno}public ${sus}fun ${id(m.name)}(${params(m.arguments).join(', ')}): ${type} =\n    Krtc.factory.${e.name}_${m.name}(${argNames(m.arguments)})`);
          } else if (inherited.has(`op:${m.name}(${erasedKey(m.arguments)})`)) {
            // Redeclared with a narrower return type (e.g. clone()); overrides may not repeat defaults.
            body.push(`${d}\n${anno}override ${sus}fun ${id(m.name)}(${params(m.arguments, false).join(', ')})${ret}`);
          } else {
            body.push(`${d}\n${anno}public ${sus}fun ${id(m.name)}(${params(m.arguments).join(', ')})${ret}`);
          }
          break;
        }
        default: break;
      }
    }
  }

  const extra = [];
  if (e.external) {
    extra.push(`Partial of \`${e.name}\`, defined outside krtc scope${webrefIndex.has(e.name) ? ` (\`${webrefIndex.get(e.name).spec}\`)` : ''}.`);
    if (DOM_BOUND_TARGETS.has(e.name)) extra.push('DOM-bound: needs a platform-neutral design (stage 5).');
  }
  const head = e.external
    ? `/**\n${extra.map((l) => ` * ${l}`).join('\n')}\n */`
    : doc(e.spec, `${e.spec}:${e.name}`, anchor(e.spec, e.name), extra);
  const members = [...body];
  if (companion.length) members.push(`public companion object {\n${indent(companion.join('\n\n'))}\n}`);
  const sup = supers.length ? ` : ${[...new Set(supers)].join(', ')}` : '';
  const block = members.length ? ` {\n${indent(members.join('\n\n'))}\n}` : '';
  return `${head}\n${e.external ? '' : extAnno(e.spec)}public ${interfaceKind} ${e.name}${sup}${block}`;
}

function dictFields(name) {
  if (name === 'EventInit') return EVENT_INIT_FIELDS;
  const e = reg.get(name);
  if (!e || e.kind !== 'dictionary') {
    if (name) externals.set(name, 'dictionary-parent');
    return [];
  }
  const parent = e.def?.inheritance;
  const inherited = parent ? dictFields(parent).map((f) => ({ ...f, inherited: true })) : [];
  const own = [];
  for (const { spec, def } of [...(e.def ? [{ spec: e.spec, def: e.def }] : []), ...e.parts]) {
    for (const m of def.members ?? []) {
      if (m.type !== 'field') continue;
      let type = kt(m.idlType);
      let def = null;
      if (m.required) def = null;
      else if (m.default) def = defaultExpr(m.default, m.idlType, type);
      else { type = nullable(type); def = 'null'; }
      own.push({ name: m.name, type, def, spec, owner: name, inherited: false });
    }
  }
  return [...inherited, ...own];
}

function emitDictionary(e) {
  const parent = e.def?.inheritance;
  const fields = dictFields(e.name);
  const isParent = dictParents.has(e.name);
  const head = `${doc(e.spec, `${e.spec}:${e.name}`, anchor(e.spec, e.name))}\n${extAnno(e.spec)}`;
  const superCall = parent ? ` : ${parent}(${fields.filter((f) => f.inherited).map((f) => `${id(f.name)} = ${id(f.name)}`).join(', ')})` : '';
  const param = (f) => {
    const fdoc = f.inherited ? '' : `${doc(f.spec, `${f.spec}:${f.owner}.${f.name}`, anchor(f.spec, f.owner, f.name), SPECS[f.spec].tier === 'extended' ? ['Extended-tier field.'] : [])}\n`;
    let decl;
    if (isParent) decl = f.inherited ? '' : 'public open val ';
    else decl = f.inherited ? 'override val ' : 'public val ';
    return `${fdoc}${decl}${id(f.name)}: ${f.type}${f.def ? ` = ${f.def}` : ''},`;
  };
  if (isParent) {
    const ps = fields.length ? `(\n${indent(fields.map(param).join('\n'))}\n)` : '()';
    return `${head}public open class ${e.name}${ps}${superCall}`;
  }
  if (!fields.length) {
    return `${head}public class ${e.name}${superCall || (parent ? ` : ${parent}()` : '')} {\n    override fun equals(other: Any?): Boolean = other is ${e.name}\n    override fun hashCode(): Int = ${kstr(e.name)}.hashCode()\n    override fun toString(): String = ${kstr(`${e.name}()`)}\n}`;
  }
  return `${head}public data class ${e.name}(\n${indent(fields.map(param).join('\n'))}\n)${superCall}`;
}

function emitEnum(e) {
  const values = e.def.values.map((v) => v.value);
  const consts = enumConsts(values);
  const entries = values.map((v, i) => `${doc(e.spec, `${e.spec}:${e.name}."${v}"`, anchor(e.spec, e.name, v))}\n${consts[i]}(${kstr(v)}),`);
  return `${doc(e.spec, `${e.spec}:${e.name}`, anchor(e.spec, e.name))}\n${extAnno(e.spec)}public enum class ${e.name}(public val value: String) {\n${indent(entries.join('\n'))}\n    ;\n\n    public companion object {\n        public fun fromValue(value: String): ${e.name}? = entries.firstOrNull { it.value == value }\n    }\n}`;
}

function emitTypedef(e) {
  const d = `${doc(e.spec, `${e.spec}:${e.name}`, anchor(e.spec, e.name))}\n${extAnno(e.spec)}`;
  const t = e.def.idlType;
  if (t.union && !t.nullable) {
    kt(t, e.name); // registers union under the typedef's own name; emitted with the unions
    unions.get(e.name).doc = d;
    return null;
  }
  return `${d}public typealias ${e.name} = ${kt(t)}`;
}

function emitCallback(e) {
  const { suspend, type } = returnType(e.def.idlType);
  const args = (e.def.arguments ?? []).map((a) => `${id(a.name)}: ${a.optional ? nullable(kt(a.idlType)) : kt(a.idlType)}`);
  return `${doc(e.spec, `${e.spec}:${e.name}`, anchor(e.spec, e.name))}\n${extAnno(e.spec)}public typealias ${e.name} = ${suspend ? 'suspend ' : ''}(${args.join(', ')}) -> ${type}`;
}

function emitUnion(name, alts) {
  const head = alts.doc ?? '/** Generated from an inline IDL union type. */\n';
  const vs = alts.map((a) => `@JvmInline\npublic value class ${a.alt}(public val value: ${a.type}) : ${name}`);
  return `${head}public sealed interface ${name} {\n${indent(vs.join('\n\n'))}\n}`;
}

const indent = (s) => s.split('\n').map((l) => (l ? `    ${l}` : l)).join('\n');

function fileFor(parts, extraImports = []) {
  const body = parts.filter(Boolean).join('\n\n');
  const imports = [];
  if (/\bFlow</.test(body)) imports.push('kotlinx.coroutines.flow.Flow');
  if (/\bDeferred</.test(body)) imports.push('kotlinx.coroutines.Deferred');
  if (/@JvmInline/.test(body)) imports.push('kotlin.jvm.JvmInline');
  imports.push(...extraImports);
  return [
    `// GENERATED by tools/spec-inventory/generate-kotlin.mjs from @webref/idl@${webrefVersion}. DO NOT EDIT.`,
    '@file:Suppress("RedundantVisibilityModifier", "FunctionName", "PropertyName", "ObjectPropertyName", "unused")',
    '',
    `package ${KOTLIN_PACKAGE}`,
    '',
    ...(imports.length ? [...imports.sort().map((i) => `import ${i}`), ''] : []),
    body,
    '',
  ].join('\n');
}

const pascal = (s) => s.split(/[^A-Za-z0-9]/).map((p) => p[0].toUpperCase() + p.slice(1)).join('');

// ---------------------------------------------------------------------------

function main() {
  const files = new Map();
  const bySpec = new Map();
  // Typedefs first, so union typedefs are registered under their own names before anything uses them.
  const ordered = [...reg.values()].sort((a, b) => (a.kind === 'typedef' ? 0 : 1) - (b.kind === 'typedef' ? 0 : 1));
  for (const e of ordered) {
    if (BASE_TYPES.has(e.name)) continue;
    let src;
    switch (e.kind) {
      case 'interface': case 'mixin': case 'callback-interface': case 'namespace': src = emitInterface(e); break;
      case 'dictionary': src = emitDictionary(e); break;
      case 'enum': src = emitEnum(e); break;
      case 'typedef': src = emitTypedef(e); break;
      case 'callback': src = emitCallback(e); break;
      default: throw new Error(`unhandled kind ${e.kind} for ${e.name}`);
    }
    const file = e.external ? 'External' : pascal(e.spec);
    if (!bySpec.has(file)) bySpec.set(file, []);
    bySpec.get(file).push(src);
  }
  for (const [file, parts] of bySpec) files.set(`${file}.kt`, fileFor(parts));

  files.set('Unions.kt', fileFor([...unions].sort(([a], [b]) => a.localeCompare(b)).map(([n, alts]) => emitUnion(n, alts))));

  // Types referenced from scope but defined elsewhere. Enums and callbacks are cheap to mirror
  // faithfully from their defining spec; everything else becomes a placeholder to design in stage 5.
  const ext = [];
  const done = new Set();
  for (let pending; (pending = [...externals].filter(([n]) => !done.has(n) && !reg.has(n) && !BASE_TYPES.has(n))).length;) {
    for (const [n, kind] of pending) {
      done.add(n);
      const where = webrefIndex.get(n);
      const label = `\`${n}\` is defined outside krtc scope${where ? ` (\`${where.spec}\`)` : ''}`;
      if (where?.def.type === 'enum') {
        const values = where.def.values.map((v) => v.value);
        const consts = enumConsts(values);
        enumNames.add(n);
        reg.set(n, { name: n, kind: 'enum', def: where.def, parts: [], mixins: [], external: true });
        ext.push([n, `/** Mirrored: ${label}. */\npublic enum class ${n}(public val value: String) {\n${indent(values.map((v, i) => `${consts[i]}(${kstr(v)}),`).join('\n'))}\n    ;\n\n    public companion object {\n        public fun fromValue(value: String): ${n}? = entries.firstOrNull { it.value == value }\n    }\n}`]);
      } else if (where?.def.type === 'callback') {
        const { suspend, type } = returnType(where.def.idlType);
        const args = (where.def.arguments ?? []).map((a) => `${id(a.name)}: ${a.optional ? nullable(kt(a.idlType)) : kt(a.idlType)}`);
        ext.push([n, `/** Mirrored: ${label}. */\npublic typealias ${n} = ${suspend ? 'suspend ' : ''}(${args.join(', ')}) -> ${type}`]);
      } else {
        const d = `/** Placeholder: ${label}. TODO(stage 5): platform-neutral mapping. */`;
        if (kind === 'dictionary-parent') ext.push([n, `${d}\npublic open class ${n}`]);
        else ext.push([n, `${d}\npublic interface ${n}${kind === 'event' ? ' : Event' : ''}`]);
      }
    }
  }
  ext.sort(([a], [b]) => a.localeCompare(b));
  files.set('Placeholders.kt', fileFor(ext.map(([, src]) => src)));

  files.set('KrtcFactory.kt', fileFor([
    [
      '/**',
      ' * Constructors and static members of every generated interface. Implemented once per platform',
      ' * and installed into [Krtc.factory]; the interfaces\' companion objects delegate here.',
      ' */',
      `public interface KrtcFactory {\n${indent(factory.join('\n'))}\n}`,
    ].join('\n'),
  ]));

  // Write / check
  const existing = fs.existsSync(outDir) ? fs.readdirSync(outDir).filter((f) => f.endsWith('.kt')) : [];
  if (checkOnly) {
    const stale = [...files].filter(([f, c]) => !fs.existsSync(path.join(outDir, f)) || fs.readFileSync(path.join(outDir, f), 'utf8') !== c).map(([f]) => f);
    const extra = existing.filter((f) => !files.has(f));
    if (stale.length || extra.length) {
      console.error(`krtc-idl is stale: ${[...stale, ...extra.map((f) => `-${f}`)].join(', ')}. Run \`npm run generate:kotlin\`.`);
      process.exit(1);
    }
    console.log(`krtc-idl up to date (${files.size} files).`);
    return;
  }
  fs.mkdirSync(outDir, { recursive: true });
  for (const f of existing) if (!files.has(f)) fs.rmSync(path.join(outDir, f));
  for (const [f, c] of files) fs.writeFileSync(path.join(outDir, f), c);

  const count = (k) => [...reg.values()].filter((e) => e.kind === k && !BASE_TYPES.has(e.name)).length;
  console.log(`@webref/idl ${webrefVersion} → ${path.relative(repoRoot, outDir)} (${files.size} files)`);
  console.log(`  interfaces ${count('interface')}, mixins ${count('mixin')}, dictionaries ${count('dictionary')}, enums ${count('enum')}, typedefs ${count('typedef')}, callbacks ${count('callback')}`);
  console.log(`  unions ${unions.size}, placeholders ${ext.length}, factory members ${factory.length}`);
}

main();
