// Writes the pictures of the README that are made from other files, into images/readme/:
//
// - radar-dark.svg, radar-light.svg: the radar of docs/features.yaml (src/lib/features.mjs), the
//   same drawing the guide shows. GitHub picks one by the reader's theme (<picture> in README.md).
// - hero.webp: the TV, tablet and phone Home screenshots of images/screenshots/ in device frames.
//
// They are committed, because GitHub shows the README without a build. Run from devsite/ after
// changing docs/features.yaml or one of the three Home screenshots:
//
//   npm run readme-assets            # write them
//   npm run readme-assets -- --check # fail when a radar is not what features.yaml gives (pages.yml)
//
// --check compares the radars only: the hero's bytes depend on the image library's version.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import { radarSvg } from '../src/lib/features.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, '../..');
const OUT = path.join(ROOT, 'images/readme');
const SHOTS = path.join(ROOT, 'images/screenshots');
const check = process.argv.includes('--check');

const radars = { 'radar-dark.svg': radarSvg({ theme: 'dark' }), 'radar-light.svg': radarSvg({ theme: 'light' }) };

if (check) {
  const stale = Object.entries(radars).filter(([name, svg]) => {
    const file = path.join(OUT, name);
    return !fs.existsSync(file) || fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') !== svg;
  });
  if (!fs.existsSync(path.join(OUT, 'hero.webp'))) stale.push(['hero.webp']);
  if (stale.length) {
    console.error(`Out of date: ${stale.map(([name]) => `images/readme/${name}`).join(', ')}.\nRun \`npm run readme-assets\` in devsite/ and commit the result.`);
    process.exit(1);
  }
  console.log('readme-assets: the radars match docs/features.yaml');
  process.exit(0);
}

fs.mkdirSync(OUT, { recursive: true });
for (const [name, svg] of Object.entries(radars)) fs.writeFileSync(path.join(OUT, name), svg);

// The hero: three screens in plain frames, on a transparent ground so it sits on GitHub's light
// and dark pages alike. Frame colours are surfaceContainer and outlineVariant (VISION.md §3.1).
const W = 1600;
const H = 880;
const FRAME = '#121A2C';
const EDGE = '#2A3650';
const devices = [
  // The TV at the back, the tablet and the phone in front of it.
  { file: 'tv-home.webp', x: 150, y: 24, w: 1040, h: 585, bezel: 12, radius: 14, stand: true },
  { file: 'tablet-home.webp', x: 890, y: 440, w: 640, h: 370, bezel: 18, radius: 28 },
  { file: 'home.webp', x: 70, y: 380, w: 216, h: 438, bezel: 10, radius: 30 },
];

let svg = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">\n<defs>\n`;
devices.forEach((d, i) => {
  svg += `<clipPath id="screen${i}"><rect x="${d.x}" y="${d.y}" width="${d.w}" height="${d.h}" rx="${Math.max(d.radius - d.bezel, 4)}"/></clipPath>\n`;
});
svg += '</defs>\n';
for (const [i, d] of devices.entries()) {
  // Fill the screen with the screenshot, cropped from the top when the ratios differ.
  const png = await sharp(path.join(SHOTS, d.file)).resize(d.w * 2, d.h * 2, { fit: 'cover', position: 'top' }).png().toBuffer();
  if (d.stand) {
    const mid = d.x + d.w / 2;
    svg += `<rect x="${mid - 60}" y="${d.y + d.h + d.bezel}" width="120" height="34" fill="${FRAME}" stroke="${EDGE}" stroke-width="2"/>\n`;
    svg += `<rect x="${mid - 210}" y="${d.y + d.h + d.bezel + 32}" width="420" height="14" rx="7" fill="${FRAME}" stroke="${EDGE}" stroke-width="2"/>\n`;
  }
  svg += `<rect x="${d.x - d.bezel}" y="${d.y - d.bezel}" width="${d.w + 2 * d.bezel}" height="${d.h + 2 * d.bezel}" rx="${d.radius}" fill="${FRAME}" stroke="${EDGE}" stroke-width="2"/>\n`;
  svg += `<image x="${d.x}" y="${d.y}" width="${d.w}" height="${d.h}" clip-path="url(#screen${i})" xlink:href="data:image/png;base64,${png.toString('base64')}"/>\n`;
}
svg += '</svg>\n';
await sharp(Buffer.from(svg)).webp({ quality: 86 }).toFile(path.join(OUT, 'hero.webp'));

console.log(`readme-assets: ${Object.keys(radars).join(', ')} and hero.webp in images/readme/`);
