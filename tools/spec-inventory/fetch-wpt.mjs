// Fetches the WebRTC-related web-platform-tests directories at the revision pinned in wpt.lock
// into .cache/wpt (sparse, blobless, shallow — a few MB instead of the whole repository).
//
// Usage: npm run wpt:fetch              (check out the pinned revision)
//        npm run wpt:fetch -- --update  (move the pin to WPT master HEAD)
//
// The checkout is only ever read as text by map-wpt.mjs; nothing from it is executed.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WPT_REPO, WPT_DIRS } from './scope.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.join(here, '.cache/wpt');
const lockPath = path.join(here, 'wpt.lock');
const update = process.argv.includes('--update');
const git = (...args) => execFileSync('git', args, { cwd: dir, stdio: ['ignore', 'pipe', 'inherit'] }).toString().trim();

if (!fs.existsSync(path.join(dir, '.git'))) {
  fs.mkdirSync(dir, { recursive: true });
  execFileSync('git', ['clone', '--quiet', '--filter=blob:none', '--sparse', '--no-checkout', '--depth', '1', WPT_REPO, dir], { stdio: 'inherit' });
}
git('sparse-checkout', 'set', '--no-cone', ...Object.keys(WPT_DIRS).map((d) => `/${d}`));

let sha;
if (update || !fs.existsSync(lockPath)) {
  git('fetch', '--quiet', '--depth', '1', '--filter=blob:none', 'origin', 'HEAD');
  sha = git('rev-parse', 'FETCH_HEAD');
} else {
  sha = fs.readFileSync(lockPath, 'utf8').trim();
  git('fetch', '--quiet', '--depth', '1', '--filter=blob:none', 'origin', sha);
}
git('checkout', '--quiet', '--detach', sha);
fs.writeFileSync(lockPath, `${sha}\n`);

const missing = Object.keys(WPT_DIRS).filter((d) => !fs.existsSync(path.join(dir, d)));
console.log(`WPT ${sha} (${git('log', '-1', '--format=%cs')}) → ${path.relative(process.cwd(), dir) || '.'}`);
if (missing.length) console.log(`  not present upstream: ${missing.join(', ')}`);
