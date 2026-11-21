import type { PublicMap } from './types.ts';

/** IDs remain valid routes even when a map has a preferred path. */
export function mapRoutes(
  maps: Record<string, PublicMap>,
): Map<string, PublicMap> {
  const routes = new Map<string, PublicMap>();
  for (const map of Object.values(maps)) {
    if (map.path !== undefined && typeof map.path !== 'string')
      throw new Error(`Invalid map path: ${map.id}`);
    for (const path of new Set([map.id, map.path ?? map.id])) {
      if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(path))
        throw new Error(`Invalid map path: ${path}`);
      if (['assets', 'licenses', 'src'].includes(path.toLowerCase()))
        throw new Error(`Reserved map path: ${path}`);
      if (routes.has(path)) throw new Error(`Duplicate map path: ${path}`);
      routes.set(path, map);
    }
  }
  return routes;
}

export function mapAtUrl(
  routes: Map<string, PublicMap>,
  root: URL,
  url: URL,
): PublicMap | undefined {
  if (url.origin !== root.origin || !url.pathname.startsWith(root.pathname))
    return undefined;
  const path = url.pathname.slice(root.pathname.length).replace(/\/$/, '');
  return routes.get(path);
}

export function mapUrl(root: URL, map?: PublicMap): URL {
  return map ? new URL(`${map.path ?? map.id}/`, root) : new URL(root);
}

/** Pin relative links to the app root before history changes the current path. */
export function applicationRoot(): URL {
  const root = new URL('./', document.baseURI);
  const base = document.querySelector('base');
  if (!base) throw new Error('Missing application base URL');
  base.href = root.href;
  return root;
}
