// docs/features.yaml as data: the feature lists of the guide, the "what runs where" table and the
// radar chart (on the site and, as images/readme/radar-*.svg, in the README) all come from here.
// Plain JavaScript, so scripts/readme-assets.mjs and the Astro components share it.

import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';

// Astro runs from devsite/ in dev and in the build; so do the npm scripts.
const ROOT = path.resolve(process.cwd(), '..');

export const DEVICES = [
  { id: 'phone', label: 'Phone' },
  { id: 'tablet', label: 'Tablet' },
  { id: 'tv', label: 'Android TV' },
];

/** The areas and features of docs/features.yaml, checked: a typo fails the build. */
export function loadFeatures() {
  const data = YAML.parse(fs.readFileSync(path.join(ROOT, 'docs/features.yaml'), 'utf8'));
  const areaIds = new Set(data.areas.map((a) => a.id));
  const deviceIds = new Set(DEVICES.map((d) => d.id));
  const seen = new Set();
  for (const f of data.features) {
    const where = `docs/features.yaml, feature "${f.id}"`;
    if (!f.id || seen.has(f.id)) throw new Error(`${where}: missing or repeated id`);
    seen.add(f.id);
    if (!areaIds.has(f.area)) throw new Error(`${where}: unknown area "${f.area}"`);
    if (!f.title) throw new Error(`${where}: no title`);
    if (!Array.isArray(f.on) || !f.on.length || f.on.some((d) => !deviceIds.has(d))) throw new Error(`${where}: "on" must list phone, tablet or tv`);
    // The device area is shown on the tablet page and the TV page only.
    if (f.area === 'device' && f.on.includes('phone')) throw new Error(`${where}: the "device" area is for the tablet layouts and the TV app; a feature the phone has goes in another area`);
    if (f.image && !fs.existsSync(path.join(ROOT, 'images/screenshots', f.image))) throw new Error(`${where}: no images/screenshots/${f.image}`);
  }
  return data;
}

/** Per area: how many features the phone and tablet app has, and how many the TV app. */
export function countsByArea(data = loadFeatures()) {
  return data.areas.map((area) => {
    const features = data.features.filter((f) => f.area === area.id);
    return {
      ...area,
      handheld: features.filter((f) => f.on.includes('phone') || f.on.includes('tablet')).length,
      tv: features.filter((f) => f.on.includes('tv')).length,
    };
  });
}

// Colours: the tokens of docs/identity/VISION.md §3 (primary and tertiary of each scheme). The
// pair is close for deuteranopia (the dataviz validator warns), so the series never rely on colour:
// the TV line is dashed with diamond markers, both are named in the legend, and the counts are
// printed at every axis.
export const RADAR_THEMES = {
  dark: { surface: '#070B16', card: '#0D1322', grid: '#2A3650', text: '#E4FBFF', muted: '#9FB3C8', handheld: '#4FD8F0', tv: '#B9A3FF' },
  light: { surface: '#F3FBFF', card: '#FFFFFF', grid: '#BCC9D8', text: '#0B1326', muted: '#3F4F64', handheld: '#007A8E', tv: '#6B4FD1' },
};

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const r1 = (n) => Math.round(n * 10) / 10;

/**
 * The radar as an SVG string: one axis per area, the number of features per area for the phone
 * and tablet app and for the TV app.
 *
 * @param theme a key of RADAR_THEMES, or 'css' for the site, where the colours are CSS variables
 *   (--radar-*) that follow the theme switch.
 * @param frame draws the rounded card behind the chart (the README image); the site has its own.
 */
export function radarSvg({ theme = 'dark', frame = true, data = loadFeatures() } = {}) {
  const c =
    theme === 'css'
      ? Object.fromEntries(Object.keys(RADAR_THEMES.dark).map((k) => [k, `var(--radar-${k})`]))
      : RADAR_THEMES[theme];
  const areas = countsByArea(data);
  const n = areas.length;
  const W = 760;
  const H = 580;
  const cx = W / 2;
  const cy = 290;
  const R = 170;
  const top = Math.max(...areas.flatMap((a) => [a.handheld, a.tv]));
  const step = top > 12 ? 4 : top > 6 ? 3 : 2;
  const max = Math.ceil(top / step) * step;
  const angle = (i) => -Math.PI / 2 + (i * 2 * Math.PI) / n;
  const point = (i, v, extra = 0) => {
    const r = (v / max) * R + extra;
    return [r1(cx + r * Math.cos(angle(i))), r1(cy + r * Math.sin(angle(i)))];
  };
  const polygon = (key) => areas.map((a, i) => point(i, a[key]).join(',')).join(' ');

  let s = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-labelledby="radar-title radar-desc" font-family="system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif">\n`;
  s += `<title id="radar-title">What Ephyra adds to Findroid, per area and per app</title>\n`;
  s += `<desc id="radar-desc">${esc(areas.map((a) => `${a.title}: ${a.handheld} on phone and tablet, ${a.tv} on Android TV`).join('. '))}.</desc>\n`;
  if (frame) s += `<rect x="1" y="1" width="${W - 2}" height="${H - 2}" rx="24" fill="${c.surface}" stroke="${c.grid}"/>\n`;

  // Rings and spokes, recessive.
  for (let v = step; v <= max; v += step) {
    const ring = areas.map((_, i) => point(i, v).join(',')).join(' ');
    s += `<polygon points="${ring}" fill="none" stroke="${c.grid}" stroke-width="1"/>\n`;
    const [x, y] = point(0, v);
    s += `<text x="${x + 6}" y="${y + 4}" font-size="11" fill="${c.muted}">${v}</text>\n`;
  }
  areas.forEach((_, i) => {
    const [x, y] = point(i, max);
    s += `<line x1="${cx}" y1="${cy}" x2="${x}" y2="${y}" stroke="${c.grid}" stroke-width="1"/>\n`;
  });

  // The two series: fill, 2px line, markers with a surface ring.
  s += `<polygon points="${polygon('handheld')}" fill="${c.handheld}" fill-opacity="0.16" stroke="${c.handheld}" stroke-width="2" stroke-linejoin="round"/>\n`;
  s += `<polygon points="${polygon('tv')}" fill="${c.tv}" fill-opacity="0.16" stroke="${c.tv}" stroke-width="2" stroke-dasharray="6 4" stroke-linejoin="round"/>\n`;
  areas.forEach((a, i) => {
    const [hx, hy] = point(i, a.handheld);
    const [tx, ty] = point(i, a.tv);
    s += `<circle cx="${hx}" cy="${hy}" r="5" fill="${c.handheld}" stroke="${c.surface}" stroke-width="2"><title>${esc(a.title)}: ${a.handheld} on phone and tablet</title></circle>\n`;
    s += `<rect x="${r1(tx - 4.5)}" y="${r1(ty - 4.5)}" width="9" height="9" transform="rotate(45 ${tx} ${ty})" fill="${c.tv}" stroke="${c.surface}" stroke-width="2"><title>${esc(a.title)}: ${a.tv} on Android TV</title></rect>\n`;
  });

  // Axis labels with both counts, in text colours; the marks beside the numbers carry the series.
  areas.forEach((a, i) => {
    const [x, y] = point(i, max, 26);
    const cos = Math.cos(angle(i));
    const anchor = Math.abs(cos) < 0.2 ? 'middle' : cos > 0 ? 'start' : 'end';
    const dy = Math.sin(angle(i)) < -0.5 ? -14 : Math.sin(angle(i)) > 0.5 ? 8 : -4;
    s += `<text x="${x}" y="${r1(y + dy)}" text-anchor="${anchor}" font-size="15" font-weight="600" fill="${c.text}">${esc(a.title)}</text>\n`;
    // Counts: "● 7   ◆ 0", laid out from the anchor.
    const width = 78;
    const x0 = anchor === 'start' ? x : anchor === 'end' ? x - width : x - width / 2;
    const cyRow = r1(y + dy + 17);
    s += `<circle cx="${r1(x0 + 5)}" cy="${cyRow}" r="4.5" fill="${c.handheld}"/>`;
    s += `<text x="${r1(x0 + 15)}" y="${r1(cyRow + 4.5)}" font-size="13" fill="${c.muted}">${a.handheld}</text>`;
    s += `<rect x="${r1(x0 + 42)}" y="${r1(cyRow - 4)}" width="8" height="8" transform="rotate(45 ${r1(x0 + 46)} ${cyRow})" fill="${c.tv}"/>`;
    s += `<text x="${r1(x0 + 57)}" y="${r1(cyRow + 4.5)}" font-size="13" fill="${c.muted}">${a.tv}</text>\n`;
  });

  // Legend.
  const ly = H - 34;
  s += `<line x1="${cx - 190}" y1="${ly}" x2="${cx - 162}" y2="${ly}" stroke="${c.handheld}" stroke-width="2"/><circle cx="${cx - 176}" cy="${ly}" r="5" fill="${c.handheld}" stroke="${c.surface}" stroke-width="2"/>`;
  s += `<text x="${cx - 154}" y="${ly + 5}" font-size="14" fill="${c.text}">Phone and tablet</text>\n`;
  s += `<line x1="${cx + 34}" y1="${ly}" x2="${cx + 62}" y2="${ly}" stroke="${c.tv}" stroke-width="2" stroke-dasharray="6 4"/><rect x="${cx + 43.5}" y="${ly - 4.5}" width="9" height="9" transform="rotate(45 ${cx + 48} ${ly})" fill="${c.tv}" stroke="${c.surface}" stroke-width="2"/>`;
  s += `<text x="${cx + 70}" y="${ly + 5}" font-size="14" fill="${c.text}">Android TV</text>\n`;
  s += `</svg>\n`;
  return s;
}

/** The verdicts of docs/upstream-ledger.md, counted: [{ verdict, count }], most frequent first. */
export function ledgerCounts() {
  const text = fs.readFileSync(path.join(ROOT, 'docs/upstream-ledger.md'), 'utf8');
  const start = text.indexOf('## Commits');
  const counts = new Map();
  for (const line of text.slice(start).split('\n')) {
    const cells = line.split('|').map((c) => c.trim());
    // | commit | date | kind | what | verdict | where |
    if (cells.length < 7 || !cells[1].startsWith('[`')) continue;
    const verdict = cells[5].replace(/[*`]/g, '').toLowerCase();
    counts.set(verdict, (counts.get(verdict) ?? 0) + 1);
  }
  return [...counts].map(([verdict, count]) => ({ verdict, count })).sort((a, b) => b.count - a.count);
}
