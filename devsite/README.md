# Documentation site

The source of the documentation of Ephyra, published next to the landing page (`site/`): Astro
Starlight, in two halves with one look and one build.

| Half | Address | What | Where it is written |
|---|---|---|---|
| Guide | https://midasvo.nl/ephyra/guide/ | Using Ephyra: install, features, tablet, TV | By hand, in `src/content/docs/guide/*.mdx`. The feature lists, the "What runs where" table and the radar come from `docs/features.yaml` |
| Development | https://midasvo.nl/ephyra/dev/ | Building Ephyra (#928): areas, architecture, decisions, with the model in LikeC4 | Almost nowhere here. The pages are the Markdown of the repository, so GitHub, an agent in a checkout and the site show the same words |

| Where | What |
|---|---|
| `astro.config.mjs` | The site and its sidebar. `src/routeData.ts` shows each half its own part of it |
| `src/content/docs/guide/` | The guide's pages |
| `src/lib/features.mjs` | Reads and checks `docs/features.yaml`, counts it and draws the radar; also counts the verdicts of `docs/upstream-ledger.md` |
| `src/components/` | `FeatureList`, `FeatureMatrix`, `Radar`, `Ledger` (drawn from the data above) and `Shots` (screenshots from `images/screenshots/`, with a larger view on click) |
| `scripts/readme-assets.mjs` | Writes `images/readme/` for the README: the radar as SVG, light and dark, and the hero of three screenshots in device frames. Committed, because GitHub shows the README without a build |
| `scripts/sync-docs.mjs` | The list of Markdown files that become development pages (`PAGES`). Copies them into `src/content/docs/dev/` with a title from their first heading, rewrites their links, and writes the areas page (from `docs/areas.yaml`), the decisions page, the raw Markdown under `dev/raw/` and `dev/llms.txt`. What it writes is ignored by git |
| `src/content/docs/dev/index.mdx`, `dev/architecture/model.mdx` | The only hand-written development pages: the start page and the page around the model |
| `../docs/architecture/model/` | The LikeC4 model. `modules.generated.c4` is written by `GeneratedDocsTest` in `architecture/` from the build files; the other `.c4` files by hand. `likec4.config.json` starts the viewer on the context view and adds `ephyra.css`, the viewer's colours; `npm run model` builds it dark, like the site |
| `src/styles/ephyra.css` | Colours and type from `docs/identity/VISION.md` |
| `scripts/compose.mjs` | Puts `site/`, this site and the screenshots together as Pages serves them; `pages.yml` uses it |

## Commands

Node 24. From this folder:

```sh
npm ci                # once
npm run dev           # live preview at http://localhost:4321/ephyra/guide/ (no model); copies an edited doc again
npm run build         # pages and model into dist/
npm run pages         # dist/, site/ and the screenshots into .pages/ephyra/, as Pages serves them
npm run readme-assets # the README's radar and hero, after a change to docs/features.yaml or a Home screenshot
npm run check-model   # validate the LikeC4 model
npx likec4 start ../docs/architecture/model   # edit the model with live reload
```

After `npm run build && npm run pages`, the `devsite` entry of `.claude/launch.json` serves
`.pages/` at http://localhost:8766/ephyra/ (the landing page), with the guide at `guide/` and the
development site at `dev/`.

## Adding a feature

Add an entry to `docs/features.yaml` (the comment at its top says what the fields are). The guide
page of its area lists it, the table and the radar count it. Then `npm run readme-assets` and
commit `images/readme/`: `pages.yml` fails while the README's radar is not what the list gives.

## Adding a guide page

Write `src/content/docs/guide/<name>.mdx` and add it to the sidebar in `astro.config.mjs`. Links
to other pages are absolute, with the base: `/ephyra/guide/install/`.

## Adding a development page

Write the Markdown where it belongs in the repository (`docs/…`), then add it to `PAGES` in
`scripts/sync-docs.mjs` and, if it starts a new section, to the sidebar in `astro.config.mjs`. A
new top-level sidebar entry of the development half also goes in `DEV_LABELS` in
`src/routeData.ts`. Links between Markdown files keep working on GitHub; on the site they point at
the page, or at GitHub for a file that is not a page.

When a module or a dependency between modules changes, `./gradlew :architecture:test` fails until
the generated files are written again. From the repository root, not `devsite/`:

```sh
cd ..
EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'
```
