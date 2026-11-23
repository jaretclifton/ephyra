// Copies the repository's Markdown into the Starlight content folder before a build (#928).
//
// The Markdown in the repository is the source: it is what GitHub shows and what an agent reads.
// This script makes a site page of each file in PAGES: the first `# ` heading becomes the title,
// links to other pages become site links, links to anything else go to GitHub. It also writes the
// pages that are lists of other files (the areas, the decisions), a copy of each source file as
// raw Markdown next to its page, and llms.txt. Everything it writes is ignored by git.
//
// Run from devsite/: `node scripts/sync-docs.mjs` (part of `npm run build` and `npm run dev`).

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, '../..');
const SITE = path.resolve(here, '..');
const CONTENT = path.join(SITE, 'src/content/docs');
const PUBLIC = path.join(SITE, 'public');
const BASE = '/ephyra/dev/';
const SITE_URL = 'https://midasvo.nl' + BASE;
const GITHUB = 'https://github.com/midasvo/ephyra';

/** The folders and files under src/content/docs that this script owns (the rest is hand-written). */
const GENERATED = ['areas', 'architecture/index.md', 'architecture/modules.md', 'architecture/rules.md', 'architecture/history', 'decisions', 'working'];

/** Source file (from the repository root) → page (under src/content/docs), in sidebar order. */
const PAGES = [];
const add = (src, dest, extra = {}) => PAGES.push({ src, dest, ...extra });

const list = (dir, re = /\.md$/) =>
  fs.readdirSync(path.join(ROOT, dir)).filter((f) => re.test(f)).sort().map((f) => `${dir}/${f}`);

for (const f of list('docs/areas')) add(f, `areas/${path.basename(f)}`);
add('docs/architecture/README.md', 'architecture/index.md');
add('docs/architecture/modules.md', 'architecture/modules.md');
add('docs/architecture/rules.md', 'architecture/rules.md');
for (const f of list('docs/architecture', /^\d-.*\.md$/)) add(f, `architecture/history/${path.basename(f)}`, { history: true });
for (const f of list('docs/architecture/adr')) add(f, `decisions/architecture/${path.basename(f)}`, { adr: 'Architecture' });
for (const f of list('docs/tv-research/adr')) add(f, `decisions/tv/${path.basename(f)}`, { adr: 'TV' });
add('CLAUDE.md', 'working/claude-md.md', { title: 'CLAUDE.md: the agent instructions' });
add('docs/dev-environment.md', 'working/dev-environment.md');
add('docs/mpv-checklist.md', 'working/mpv-checklist.md');
add('docs/track-test-series.md', 'working/track-test-series.md');
add('docs/identity/VISION.md', 'working/identity.md');

const bySrc = new Map(PAGES.map((p) => [p.src, p]));
// The model folder is the interactive page on the site.
bySrc.set('docs/architecture/model', { dest: 'architecture/model.mdx' });

/** The site route of a page: `areas/downloads.md` → `/ephyra/dev/areas/downloads/`. */
function route(dest) {
  const slug = dest.replace(/\.mdx?$/, '').replace(/(^|\/)index$/, '').toLowerCase();
  return BASE + (slug ? slug + '/' : '');
}

/** A link in [srcFile] to [target], as it should be on the site. */
function rewriteLink(srcFile, target) {
  if (/^([a-z]+:|#|\/\/)/i.test(target)) return target;
  const [file, hash] = target.split('#');
  if (!file) return target;
  const repoPath = path.posix.normalize(path.posix.join(path.posix.dirname(srcFile), decodeURI(file))).replace(/\/$/, '');
  const page = bySrc.get(repoPath) ?? bySrc.get(`${repoPath}/README.md`);
  const anchor = hash ? `#${hash}` : '';
  if (page) return route(page.dest) + anchor;
  const abs = path.join(ROOT, repoPath);
  const kind = fs.existsSync(abs) && fs.statSync(abs).isDirectory() ? 'tree' : 'blob';
  return `${GITHUB}/${kind}/main/${repoPath}${anchor}`;
}

/** Rewrites the links of a Markdown text, outside code blocks and code spans. */
function rewriteLinks(srcFile, text) {
  return text
    .split(/(```[\s\S]*?```)/)
    .map((part, i) =>
      i % 2 === 1
        ? part
        : part
            .split(/(`[^`\n]*`)/)
            .map((piece, j) =>
              j % 2 === 1 ? piece : piece.replace(/\]\(([^)\s]+)(\s+"[^"]*")?\)/g, (_, t, title = '') => `](${rewriteLink(srcFile, t)}${title})`),
            )
            .join(''),
    )
    .join('');
}

/** ```mermaid blocks become <pre class="mermaid">, drawn in the browser (src/components/Footer.astro). */
const mermaidBlocks = (text) =>
  text.replace(/^```mermaid\n([\s\S]*?)^```$/gm, (_, graph) => {
    const escaped = graph.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return `<pre class="mermaid">${escaped.replace(/\n\s*\n/g, '\n').trimEnd()}</pre>`;
  });

const yamlString = (s) => JSON.stringify(s);

function frontmatter(fields) {
  const lines = Object.entries(fields)
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => (typeof v === 'object' ? `${k}:\n${Object.entries(v).map(([a, b]) => `  ${a}: ${yamlString(b)}`).join('\n')}` : `${k}: ${yamlString(v)}`));
  return `---\n${lines.join('\n')}\n---\n\n`;
}

/** The title (first `# ` line) and the text after it. */
function splitTitle(text) {
  const m = text.match(/^# (.+)\n/m);
  if (!m) return { title: undefined, body: text };
  return { title: m[1].trim(), body: text.slice(m.index + m[0].length) };
}

/** The status line of an ADR: `**Status:** Accepted (…)` → `Accepted (…)`. */
const statusOf = (text) => text.match(/^\*\*Status:\*\*\s*(.+)$/m)?.[1].trim();

function write(file, content) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

// Start clean, so a page removed from the repository leaves the site too.
for (const g of GENERATED) fs.rmSync(path.join(CONTENT, g), { recursive: true, force: true });
for (const g of ['raw', 'llms.txt', 'fonts', 'favicon.png']) fs.rmSync(path.join(PUBLIC, g), { recursive: true, force: true });

const pages = [];
for (const page of PAGES) {
  const text = fs.readFileSync(path.join(ROOT, page.src), 'utf8').replace(/\r\n/g, '\n');
  const { title, body } = splitTitle(text);
  const pageTitle = page.title ?? title ?? path.basename(page.src, '.md');
  const raw = `raw/${page.src}`;
  const head = frontmatter({
    title: pageTitle,
    editUrl: `${GITHUB}/edit/main/${page.src}`,
    ...(page.history ? { banner: { content: 'History: this describes the code as it was when it was written. Where it differs from the code, the code is right.' } } : {}),
  });
  const source = `\n\n---\n\n<small>Source: [\`${page.src}\`](${GITHUB}/blob/main/${page.src}) · [raw Markdown](${BASE}${raw})</small>\n`;
  write(path.join(CONTENT, page.dest), head + mermaidBlocks(rewriteLinks(page.src, body)) + source);
  write(path.join(PUBLIC, raw), text);
  pages.push({ ...page, title: pageTitle, text, url: SITE_URL + route(page.dest).slice(BASE.length), raw: SITE_URL + raw });
}

// The areas page, from docs/areas.yaml, and a page for each area that has no guide yet: what the
// map knows about it (where the code is, the docs, the rules, the tests).
const areas = YAML.parse(fs.readFileSync(path.join(ROOT, 'docs/areas.yaml'), 'utf8')).areas;
const hasGuide = (a) => fs.existsSync(path.join(ROOT, `docs/areas/${a.id}.md`));
const rulesRoute = route('architecture/rules.md');
const repoLink = (p) => {
  const abs = path.join(ROOT, p);
  const kind = fs.existsSync(abs) && fs.statSync(abs).isDirectory() ? 'tree' : 'blob';
  return `[\`${p}\`](${GITHUB}/${kind}/main/${p})`;
};
const docLink = (p) => {
  const page = bySrc.get(p.replace(/\/$/, '')) ?? bySrc.get(`${p.replace(/\/$/, '')}/README.md`);
  return page ? `[\`${p}\`](${route(page.dest)})` : repoLink(p);
};
{
  const mapUrl = `${GITHUB}/blob/main/docs/areas.yaml`;
  for (const a of areas.filter((a) => !hasGuide(a))) {
    let body = `${a.summary}\n\n:::note\nThis area has no guide yet. This page is its entry in the area map ([\`docs/areas.yaml\`](${mapUrl})): where to start reading. \`CLAUDE.md\` has the project rules that apply everywhere.\n:::\n`;
    body += `\n## Where the code is\n\n${(a.paths ?? []).map((p) => `- ${repoLink(p)}`).join('\n')}\n`;
    if (a.docs?.length) body += `\n## Docs to read\n\n${a.docs.map((p) => `- ${docLink(p)}`).join('\n')}\n`;
    if (a.rules?.length) body += `\n## Rules\n\n${a.rules.map((r) => `[${r}](${rulesRoute})`).join(', ')}: the architecture rules that guard this code. Run them with \`./gradlew :architecture:test\`.\n`;
    if (a.tests?.length) body += `\n## Tests\n\n\`\`\`bash\n${a.tests.join('\n')}\n\`\`\`\n`;
    body += `\n## Also called\n\n${(a.keywords ?? []).join(', ')}\n`;
    write(path.join(CONTENT, `areas/${a.id}.md`), frontmatter({ title: a.title, editUrl: `${GITHUB}/edit/main/docs/areas.yaml` }) + body);
  }
  const rows = areas.map((a) => `| [${a.title}](${route(`areas/${a.id}.md`)}) | ${a.summary} | ${hasGuide(a) ? 'guide' : 'map entry'} |`);
  const body =
    'Start here when you know what you want to change. An area with a guide says where the code is, the ports it uses, the rules that guard it, how to test it and what went wrong before. ' +
    'An area without one shows its entry in the map: the files to open first, the docs, the rules and the tests. ' +
    `The map is [\`docs/areas.yaml\`](${mapUrl}); the \`start-work\` skill reads the same file.\n\n` +
    '| Area | What it is | |\n|---|---|---|\n' +
    rows.join('\n') +
    '\n';
  write(path.join(CONTENT, 'areas/index.md'), frontmatter({ title: 'Areas', editUrl: `${GITHUB}/edit/main/docs/areas.yaml`, sidebar: { hidden: 'true' } }).replace('hidden: "true"', 'hidden: true') + body);
  write(path.join(PUBLIC, 'raw/docs/areas.yaml'), fs.readFileSync(path.join(ROOT, 'docs/areas.yaml'), 'utf8'));
}

// The decisions page: every ADR with its status.
{
  const groups = {};
  for (const p of pages.filter((p) => p.adr)) (groups[p.adr] ??= []).push(p);
  let body = 'Every architecture decision record, with its status. An ADR says what was decided and why; where the code later moved on, the ADR says so in its status.\n';
  for (const [group, items] of Object.entries(groups)) {
    body += `\n## ${group}\n\n| Decision | Status |\n|---|---|\n`;
    for (const p of items) body += `| [${p.title}](${route(p.dest)}) | ${rewriteLinks(p.src, statusOf(p.text) ?? '').replace(/\|/g, '\\|')} |\n`;
  }
  write(path.join(CONTENT, 'decisions/index.md'), frontmatter({ title: 'Decisions' }) + body);
}

// Static files: the display font and the icon of the Ephyra site.
fs.mkdirSync(path.join(PUBLIC, 'fonts'), { recursive: true });
fs.copyFileSync(path.join(ROOT, 'site/fonts/outfit.ttf'), path.join(PUBLIC, 'fonts/outfit.ttf'));
fs.copyFileSync(path.join(ROOT, 'site/images/branding/ephyra-icon-rounded.png'), path.join(PUBLIC, 'favicon.png'));
fs.mkdirSync(path.join(SITE, 'src/assets'), { recursive: true });
fs.copyFileSync(path.join(ROOT, 'site/images/branding/ephyra-mark.svg'), path.join(SITE, 'src/assets/ephyra-mark.svg'));

// llms.txt: what an agent without the repository reads first.
{
  const section = (title, items) => `\n## ${title}\n\n${items.map((p) => `- [${p.title}](${p.raw})`).join('\n')}\n`;
  const guides = areas.filter(hasGuide);
  // The rules as rules.md (generated by GeneratedDocsTest) lists them, so a new rule needs no edit here.
  const rules = fs.readFileSync(path.join(ROOT, 'docs/architecture/rules.md'), 'utf8').match(/^\| R\d+ \|/gm) ?? [];
  if (!rules.length) throw new Error('sync-docs: no rule found in docs/architecture/rules.md');
  let txt =
    '# Ephyra development\n\n' +
    `> How Ephyra (an Android Jellyfin client for phones, tablets and Android TV, a fork of Findroid) is built: the architecture (ports and adapters, Konsist rules R1 to R${rules.length}), the areas of the app with where the code is and how to test it, and the decisions. Every link is the raw Markdown from the repository github.com/midasvo/ephyra.\n\n` +
    `Start with the area map (${SITE_URL}raw/docs/areas.yaml): find the area by its keywords, then read its guide. The agent instructions of the repository are CLAUDE.md.\n`;
  txt += section('Area guides', pages.filter((p) => p.dest.startsWith('areas/')));
  txt += `\nAreas without a guide yet (their paths, docs, rules and tests are in the area map): ${areas.filter((a) => !hasGuide(a)).map((a) => a.id).join(', ')}.\n`;
  txt += section('Architecture', pages.filter((p) => p.dest.startsWith('architecture/') && !p.history));
  txt += section('Decisions', pages.filter((p) => p.adr));
  txt += section('Working here', pages.filter((p) => p.dest.startsWith('working/')));
  txt += section('Optional', pages.filter((p) => p.history));
  txt += `\nThe C4 model of the architecture (LikeC4 source): ${GITHUB}/tree/main/docs/architecture/model. Interactive: ${SITE_URL}model/\n`;
  write(path.join(PUBLIC, 'llms.txt'), txt);
  if (!guides.length) console.warn('No area has a guide yet');
}

console.log(`sync-docs: ${pages.length} pages, ${areas.length} areas`);
