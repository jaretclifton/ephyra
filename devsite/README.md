# Development site

The source of https://midasvo.nl/ephyra/dev/ (#928): Astro Starlight, with the architecture model
in LikeC4. It has almost no text of its own. The pages are the Markdown of the repository, so
GitHub, an agent in a checkout and the site show the same words.

| Where | What |
|---|---|
| `scripts/sync-docs.mjs` | The list of Markdown files that become pages (`PAGES`). Copies them into `src/content/docs/` with a title from their first heading, rewrites their links, and writes the areas page (from `docs/areas.yaml`), the decisions page, the raw Markdown under `raw/` and `llms.txt`. What it writes is ignored by git |
| `src/content/docs/index.mdx`, `architecture/model.mdx` | The only hand-written pages: the start page and the page around the model |
| `../docs/architecture/model/` | The LikeC4 model. `modules.generated.c4` is written by `GeneratedDocsTest` in `architecture/` from the build files; the other `.c4` files by hand |
| `src/styles/ephyra.css` | Colours and type from `docs/identity/VISION.md` |
| `scripts/compose.mjs` | Puts `site/` and this site together as Pages serves them; `pages.yml` uses it |

## Commands

Node 24. From this folder:

```sh
npm ci                # once
npm run dev           # live preview at http://localhost:4321/ephyra/dev/ (no model); copies an edited doc again
npm run build         # pages and model into dist/
npm run pages         # dist/ and site/ into .pages/ephyra/, as Pages serves them
npm run check-model   # validate the LikeC4 model
npx likec4 start ../docs/architecture/model   # edit the model with live reload
```

After `npm run build && npm run pages`, the `devsite` entry of `.claude/launch.json` serves
`.pages/` at http://localhost:8766/ephyra/dev/.

## Adding a page

Write the Markdown where it belongs in the repository (`docs/…`), then add it to `PAGES` in
`scripts/sync-docs.mjs` and, if it starts a new section, to the sidebar in `astro.config.mjs`.
Links between Markdown files keep working on GitHub; on the site they point at the page, or at
GitHub for a file that is not a page.

When a module or a dependency between modules changes, `./gradlew :architecture:test` fails until
the generated files are written again. From the repository root, not `devsite/`:

```sh
cd ..
EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'
```
