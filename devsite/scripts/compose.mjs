// Puts the Ephyra site and the development site together as GitHub Pages serves them (#928):
// site/ unchanged at the root, devsite/dist at dev/. pages.yml uploads the result; locally,
// `npm run pages` writes it to devsite/.pages/ephyra/ for the `devsite` entry of .claude/launch.json
// (http://localhost:8766/ephyra/dev/).
//
// Usage: node scripts/compose.mjs [output folder]   (default: .pages/ephyra)

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const SITE_DIR = path.resolve(here, '../../site');
const DIST = path.resolve(here, '../dist');
const out = path.resolve(process.argv[2] ?? path.join(here, '../.pages/ephyra'));

if (!fs.existsSync(path.join(DIST, 'index.html'))) throw new Error('No devsite/dist: run `npm run build` first');
if (fs.existsSync(path.join(SITE_DIR, 'dev'))) throw new Error('site/dev exists: the development site would overwrite it');
if (!fs.existsSync(path.join(DIST, 'model/index.html'))) throw new Error('No devsite/dist/model: the LikeC4 build is missing');

fs.rmSync(out, { recursive: true, force: true });
fs.cpSync(SITE_DIR, out, { recursive: true });
fs.cpSync(DIST, path.join(out, 'dev'), { recursive: true });
console.log(`compose: ${out}`);
