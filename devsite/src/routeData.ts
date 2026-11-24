// One site, two halves (astro.config.mjs): a page under dev/ gets the development sidebar, any
// other page the guide's. Starlight has one sidebar for the whole site; this keeps the top-level
// entries of the half the page is in, and drops a previous or next link that leaves it.
import { defineRouteMiddleware } from '@astrojs/starlight/route-data';

/** The top-level sidebar labels of the development half. Every other label belongs to the guide. */
const DEV_LABELS = new Set(['Using Ephyra: the guide', 'Start', 'Areas', 'Architecture', 'Decisions', 'Working here']);

type Entry = { type: string; label: string; href?: string; entries?: Entry[] };

const links = (entries: Entry[]): Entry[] => entries.flatMap((e) => (e.type === 'group' ? links(e.entries ?? []) : [e]));

export const onRequest = defineRouteMiddleware((context) => {
  const route = context.locals.starlightRoute;
  const dev = /^\/ephyra\/dev(\/|$)/.test(context.url.pathname);
  route.sidebar = route.sidebar.filter((entry) => DEV_LABELS.has(entry.label) === dev);

  const inHalf = new Set(links(route.sidebar as Entry[]).map((l) => l.href));
  if (route.pagination.prev && !inHalf.has(route.pagination.prev.href)) route.pagination.prev = undefined;
  if (route.pagination.next && !inHalf.has(route.pagination.next.href)) route.pagination.next = undefined;
});
