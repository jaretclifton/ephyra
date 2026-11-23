// The development site of Ephyra (#928), published at https://midasvo.nl/ephyra/dev/.
// The pages are the Markdown of the repository: scripts/sync-docs.mjs copies it into
// src/content/docs before each build (those copies are not committed). See devsite/README.md.
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
  base: '/ephyra/dev',
  trailingSlash: 'always',
  integrations: [
    syncOnChange,
    starlight({
      title: 'Ephyra development',
      description: 'How Ephyra is built: the architecture, the areas of the app, the decisions and how to test a change.',
      logo: { src: './src/assets/ephyra-mark.svg', alt: 'Ephyra' },
      favicon: '/favicon.png',
      customCss: ['./src/styles/ephyra.css'],
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/midasvo/ephyra' }],
      components: { Footer: './src/components/Footer.astro' },
      sidebar: [
        { label: 'Start', items: [{ label: 'Overview', link: '/' }, { label: 'Get started', link: '/working/get-started/' }, { label: 'All areas', link: '/areas/' }] },
        { label: 'Areas', items: [{ autogenerate: { directory: 'areas' } }] },
        {
          label: 'Architecture',
          items: [
            { label: 'Ports and adapters', link: '/architecture/' },
            { label: 'Model (C4)', link: '/architecture/model/' },
            { label: 'Modules', link: '/architecture/modules/' },
            { label: 'Rules', link: '/architecture/rules/' },
            { label: 'History', collapsed: true, items: [{ autogenerate: { directory: 'architecture/history' } }] },
          ],
        },
        {
          label: 'Decisions',
          items: [
            { label: 'All decisions', link: '/decisions/' },
            { label: 'Architecture', collapsed: true, items: [{ autogenerate: { directory: 'decisions/architecture' } }] },
            { label: 'TV', collapsed: true, items: [{ autogenerate: { directory: 'decisions/tv' } }] },
          ],
        },
        { label: 'Working here', items: [{ autogenerate: { directory: 'working' } }] },
      ],
    }),
  ],
});
