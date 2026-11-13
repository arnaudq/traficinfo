import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { boundaryBounds } from '../src/territories.js';

export const repositoryRoot = new URL('../../', import.meta.url);
export const preparedDirectory = new URL('../.cache/', import.meta.url);

export async function readRegistry() {
  const config = JSON.parse(
    await readFile(new URL('mapconfig.json', repositoryRoot), 'utf8'),
  );
  const sources = JSON.parse(
    await readFile(new URL('dsconfig.json', repositoryRoot), 'utf8'),
  );
  validateRegistry(config, sources);
  return config;
}

export function validateRegistry(config, sources) {
  const ensure = (condition, message) => {
    if (!condition) throw new Error(message);
  };
  const object = (value) =>
    value && typeof value === 'object' && !Array.isArray(value);
  const text = (value) => typeof value === 'string' && value.trim().length > 0;
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
  for (const [id, boundary] of Object.entries(config.boundaries)) {
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
      ['city', 'region'].includes(boundary.type) && text(boundary.name),
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
    ensure(
      boundary.type === 'city'
        ? config.boundaries[boundary.parentId]?.type === 'region'
        : !('parentId' in boundary),
      `Invalid parent: ${id}`,
    );
  }
  for (const [id, map] of Object.entries(config.maps)) {
    ensure(
      map.id === id && config.boundaries[map.boundaryId],
      `Invalid map reference: ${id}`,
    );
    ensure(
      text(map.name) && text(map.description),
      `Missing map content: ${id}`,
    );
    ensure(!map.uri || /^https?:\/\//.test(map.uri), `Invalid uMap URL: ${id}`);
    ensure(
      Array.isArray(map.dataSources) && map.dataSources.length > 0,
      `Missing sources: ${id}`,
    );
    const selected = new Set();
    for (const selection of map.dataSources) {
      const source = sources[selection.id];
      ensure(
        source?.id === selection.id &&
          source.geoJsonEnabled !== false &&
          !selected.has(selection.id),
        `Invalid datasource: ${id}/${selection.id}`,
      );
      selected.add(selection.id);
      ensure(
        Array.isArray(selection.types) &&
          selection.types.length > 0 &&
          new Set(selection.types).size === selection.types.length &&
          selection.types.every((type) =>
            ['VEHICULES', 'BICYCLES'].includes(type),
          ),
        `Invalid populations: ${id}/${selection.id}`,
      );
    }
  }
}

export async function publicConfig(config, readFeature) {
  const boundaries = {};
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
      return [id, publicMap];
    }),
  );
  return { geoJsonBaseUrl: config.geoJsonBaseUrl, boundaries, maps };
}

/** Read prepared geometry locally; the runtime client loads it from the public bucket. */
export function mapConfig() {
  let contents;
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
          new URL(request.url, 'http://localhost').pathname !==
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
