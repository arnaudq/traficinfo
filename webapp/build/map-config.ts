import type { Plugin } from 'vite';
import type {
  Boundary,
  MapConfig,
  PublicMap,
  PublicMapConfig,
} from '../src/types.ts';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { boundaryBounds } from '../src/territories.ts';

export const repositoryRoot = new URL('../../', import.meta.url);
export const preparedDirectory = new URL('../.cache/', import.meta.url);

export async function readRegistry(): Promise<MapConfig> {
  const config: unknown = JSON.parse(
    await readFile(new URL('mapconfig.json', repositoryRoot), 'utf8'),
  );
  const sources: unknown = JSON.parse(
    await readFile(new URL('dsconfig.json', repositoryRoot), 'utf8'),
  );
  validateRegistry(config, sources);
  return config;
}

function ensure(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function text(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

export function validateRegistry(
  config: unknown,
  sources: unknown,
): asserts config is MapConfig {
  ensure(object(config) && object(sources), 'Expected configuration objects');
  ensure(text(config.geoJsonBaseUrl), 'Missing geoJsonBaseUrl');
  ensure(
    /^https?:\/\/[^?#\s]+$/.test(config.geoJsonBaseUrl),
    'Invalid geoJsonBaseUrl',
  );
  ensure(
    object(config.boundaries) && Object.keys(config.boundaries).length,
    'Missing boundaries',
  );
  ensure(
    object(config.maps) && Object.keys(config.maps).length,
    'Missing maps',
  );
  const boundaries = config.boundaries;
  for (const [id, boundary] of Object.entries(boundaries)) {
    ensure(
      object(boundary) && boundary.boundaryId === id && text(id),
      `Boundary key mismatch: ${id}`,
    );
    ensure(
      Object.keys(boundary).every((field) =>
        ['boundaryId', 'type', 'name', 'parentId', 'geoJsonPath'].includes(
          field,
        ),
      ),
      `Unknown boundary field: ${id}`,
    );
    ensure(
      (boundary.type === 'city' || boundary.type === 'region') &&
        text(boundary.name),
      `Invalid boundary: ${id}`,
    );
    const path = boundary.geoJsonPath;
    ensure(
      text(path) &&
        path.startsWith('boundaries/') &&
        path.endsWith('.geojson') &&
        !path.split('/').includes('..') &&
        !/[?#]/.test(path),
      `Invalid boundary path: ${id}`,
    );
    ensure(
      !id.startsWith('departement:') || boundary.type === 'region',
      `Invalid departement type: ${id}`,
    );
    ensure(
      !id.startsWith('epci:') || boundary.type === 'city',
      `Invalid EPCI type: ${id}`,
    );
    if (boundary.type === 'city') {
      ensure(text(boundary.parentId), `Missing parent: ${id}`);
      const parent = boundaries[boundary.parentId];
      ensure(
        object(parent) && parent.type === 'region',
        `Invalid parent: ${id}`,
      );
    } else {
      ensure(!('parentId' in boundary), `Invalid parent: ${id}`);
    }
  }
  for (const [id, map] of Object.entries(config.maps)) {
    ensure(
      object(map) &&
        map.id === id &&
        text(map.boundaryId) &&
        boundaries[map.boundaryId],
      `Invalid map reference: ${id}`,
    );
    ensure(
      text(map.name) && text(map.description),
      `Missing map content: ${id}`,
    );
    ensure(
      map.uri === undefined || (text(map.uri) && /^https?:\/\//.test(map.uri)),
      `Invalid uMap URL: ${id}`,
    );
    ensure(
      Array.isArray(map.center) &&
        map.center.length === 2 &&
        map.center.every(
          (value: unknown) =>
            typeof value === 'number' && Number.isFinite(value),
        ) &&
        Math.abs(map.center[0]) <= 180 &&
        Math.abs(map.center[1]) <= 90,
      `Invalid center: ${id}`,
    );
    ensure(
      typeof map.zoom === 'number' &&
        Number.isInteger(map.zoom) &&
        map.zoom >= 0 &&
        map.zoom <= 24,
      `Invalid zoom: ${id}`,
    );
    ensure(
      map.properties === undefined || object(map.properties),
      `Invalid properties: ${id}`,
    );
    ensure(
      map.layerProperties === undefined ||
        (object(map.layerProperties) &&
          Object.values(map.layerProperties).every(object)),
      `Invalid layer properties: ${id}`,
    );
    ensure(
      Array.isArray(map.dataSources) && map.dataSources.length > 0,
      `Missing sources: ${id}`,
    );
    const selected = new Set<string>();
    for (const selection of map.dataSources as unknown[]) {
      ensure(
        object(selection) && text(selection.id),
        `Invalid selection: ${id}`,
      );
      const source = sources[selection.id];
      ensure(
        object(source) &&
          source.id === selection.id &&
          source.geoJsonEnabled !== false &&
          !selected.has(selection.id),
        `Invalid datasource: ${id}/${selection.id}`,
      );
      selected.add(selection.id);
      ensure(
        Array.isArray(selection.types) &&
          selection.types.length > 0 &&
          new Set(selection.types).size === selection.types.length &&
          selection.types.every(
            (type: unknown) => type === 'VEHICULES' || type === 'BICYCLES',
          ),
        `Invalid populations: ${id}/${selection.id}`,
      );
    }
  }
}

export async function publicConfig(
  config: MapConfig,
  readFeature: (boundary: Boundary) => Promise<unknown>,
): Promise<PublicMapConfig> {
  const boundaries: PublicMapConfig['boundaries'] = {};
  for (const [id, boundary] of Object.entries(config.boundaries)) {
    const feature = await readFeature(boundary);
    boundaries[id] = { ...boundary, bounds: boundaryBounds(feature, id) };
  }
  const maps = Object.fromEntries(
    Object.entries(config.maps).map(([id, map]) => {
      const publicMap = Object.fromEntries(
        Object.entries(map).filter(
          ([key]) => !['properties', 'layerProperties'].includes(key),
        ),
      );
      return [id, publicMap as PublicMap];
    }),
  );
  return { geoJsonBaseUrl: config.geoJsonBaseUrl, boundaries, maps };
}

/** Read prepared geometry locally; the runtime client loads it from the public bucket. */
export function mapConfig(): Plugin {
  let contents = '';

  const load = async () => {
    const config = await readRegistry();
    const output = await publicConfig(config, async (boundary) => {
      const path = new URL(boundary.geoJsonPath, preparedDirectory);
      try {
        return JSON.parse(await readFile(path, 'utf8'));
      } catch (error) {
        throw new Error(
          `Cannot read ${fileURLToPath(path)}. Run npm run prepare:boundaries.`,
          { cause: error },
        );
      }
    });
    contents = JSON.stringify(output, null, 2) + '\n';
  };
  return {
    name: 'traficinfo-map-config',
    async buildStart() {
      await load();
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'mapconfig.json',
        source: contents,
      });
    },
    configureServer(server) {
      server.middlewares.use(async (request, response, next) => {
        if (
          new URL(request.url ?? '/', 'http://localhost').pathname !==
          '/mapconfig.json'
        )
          return next();
        try {
          await load();
          response.setHeader('Content-Type', 'application/json; charset=utf-8');
          response.end(contents);
        } catch (error) {
          next(error);
        }
      });
    },
  };
}
