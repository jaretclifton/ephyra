// The documentation site of Ephyra, published next to the landing page (site/) at
// https://midasvo.nl/ephyra/. It has two halves with one look:
// - guide/: using Ephyra (install, features, tablet, TV). Hand-written MDX in
//   src/content/docs/guide, with the feature lists drawn from docs/features.yaml.
// - dev/: developing Ephyra (#928). The Markdown of the repository: scripts/sync-docs.mjs copies
//   it into src/content/docs/dev before each build (those copies are not committed).
// src/routeData.ts shows each half its own sidebar. See devsite/README.md.
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

/**
 * In `npm run dev`, copies the repository's Markdown again when it changes, so a refresh shows
 * the edit. The build runs scripts/sync-docs.mjs once before Astro instead.
 */
const syncOnChange = {
  name: 'ephyra-sync-docs',
  hooks: {
    'astro:server:setup': ({ server }) => {
      const root = path.resolve('..');
      const sources = [path.join(root, 'docs'), path.join(root, 'CLAUDE.md')];
      server.watcher.add(sources);
      server.watcher.on('change', (file) => {
        if (!sources.some((s) => file.startsWith(s)) || file.includes(`${path.sep}model${path.sep}`)) return;
        execFileSync(process.execPath, ['scripts/sync-docs.mjs'], { stdio: 'inherit' });
      });
    },
  },
};

export default defineConfig({
  site: 'https://midasvo.nl',
  base: '/ephyra',
  trailingSlash: 'always',
  integrations: [
    syncOnChange,
    starlight({
      title: 'Ephyra',
      description: 'Ephyra, a Jellyfin client for Android phones, tablets and Android TV: how to install and use it, and how it is built.',
      logo: { src: './src/assets/ephyra-mark.svg', alt: 'Ephyra' },
      favicon: '/dev/favicon.png',
      customCss: ['./src/styles/ephyra.css'],
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/midasvo/ephyra' }],
      components: { Footer: './src/components/Footer.astro' },
      routeMiddleware: './src/routeData.ts',
      // Top-level entries whose label is in DEV_LABELS (src/routeData.ts) show under dev/, the
      // others under guide/.
      sidebar: [
        {
          label: 'Using Ephyra',
          items: [
            { label: 'Overview', link: '/guide/' },
            { label: 'Install', link: '/guide/install/' },
            { label: 'Beta', link: '/guide/beta/' },
            { label: 'Ephyra and Findroid', link: '/guide/findroid/' },
          ],
        },
        {
          label: 'Features',
          items: [
            { label: 'Downloads', link: '/guide/downloads/' },
            { label: 'Playback', link: '/guide/playback/' },
            { label: 'Subtitles and audio', link: '/guide/subtitles-and-audio/' },
            { label: 'Cast', link: '/guide/cast/' },
            { label: 'Library and Home', link: '/guide/library/' },
            { label: 'Profiles', link: '/guide/profiles/' },
            { label: 'Look', link: '/guide/look/' },
          ],
        },
        {
          label: 'Devices',
          items: [
            { label: 'Tablet and foldable', link: '/guide/tablet/' },
            { label: 'Android TV', link: '/guide/tv/' },
            { label: 'What runs where', link: '/guide/devices/' },
          ],
        },
        { label: 'Developing Ephyra', link: '/dev/' },
        { label: 'Using Ephyra: the guide', link: '/guide/' },
        { label: 'Start', items: [{ label: 'Overview', link: '/dev/' }, { label: 'All areas', link: '/dev/areas/' }] },
        { label: 'Areas', items: [{ autogenerate: { directory: 'dev/areas' } }] },
        {
          label: 'Architecture',
          items: [
            { label: 'Ports and adapters', link: '/dev/architecture/' },
            { label: 'Model (C4)', link: '/dev/architecture/model/' },
            { label: 'Modules', link: '/dev/architecture/modules/' },
            { label: 'Rules', link: '/dev/architecture/rules/' },
            { label: 'History', collapsed: true, items: [{ autogenerate: { directory: 'dev/architecture/history' } }] },
          ],
        },
        {
          label: 'Decisions',
          items: [
            { label: 'All decisions', link: '/dev/decisions/' },
            { label: 'Architecture', collapsed: true, items: [{ autogenerate: { directory: 'dev/decisions/architecture' } }] },
            { label: 'TV', collapsed: true, items: [{ autogenerate: { directory: 'dev/decisions/tv' } }] },
          ],
        },
        { label: 'Working here', items: [{ autogenerate: { directory: 'dev/working' } }] },
      ],
    }),
  ],
});
