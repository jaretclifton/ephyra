// Puts the landing page and the documentation site together as GitHub Pages serves them:
// site/ unchanged at the root, devsite/dist (guide/, dev/ and their assets) next to it, and the
// screenshots of images/screenshots/ at images/screenshots/, where the landing page expects them
// (they are kept once, in images/). pages.yml uploads the result; locally, `npm run pages` writes
// it to devsite/.pages/ephyra/ for the `site` entry of .claude/launch.json
// (http://localhost:8766/ephyra/).
//
// Usage: node scripts/compose.mjs [output folder]   (default: .pages/ephyra)

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, '../..');
const SITE_DIR = path.join(ROOT, 'site');
const SHOTS = path.join(ROOT, 'images/screenshots');
const DIST = path.resolve(here, '../dist');
const out = path.resolve(process.argv[2] ?? path.join(here, '../.pages/ephyra'));

for (const page of ['dev/index.html', 'guide/index.html'])
  if (!fs.existsSync(path.join(DIST, page))) throw new Error(`No devsite/dist/${page}: run \`npm run build\` first`);
if (!fs.existsSync(path.join(DIST, 'dev/model/index.html'))) throw new Error('No devsite/dist/dev/model: the LikeC4 build is missing');
// Nothing the build writes may replace a file of site/.
const clash = fs.readdirSync(DIST).filter((name) => fs.existsSync(path.join(SITE_DIR, name)));
if (clash.length) throw new Error(`site/ and devsite/dist both have: ${clash.join(', ')}`);
if (fs.existsSync(path.join(SITE_DIR, 'images/screenshots')))
  throw new Error('site/images/screenshots exists: the screenshots are kept in images/screenshots/ only');

fs.rmSync(out, { recursive: true, force: true });
fs.cpSync(SITE_DIR, out, { recursive: true });
fs.cpSync(DIST, out, { recursive: true });
fs.cpSync(SHOTS, path.join(out, 'images/screenshots'), { recursive: true });
console.log(`compose: ${out}`);
