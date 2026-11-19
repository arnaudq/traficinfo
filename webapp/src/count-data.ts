import type { FeatureCollection, Geometry } from 'geojson';
import type { Population, PublicMap } from './types.ts';

export type CounterProperties = Record<string, unknown>;
export type CountData = FeatureCollection<Geometry, CounterProperties>;

export interface CountLayer {
  sourceId: string;
  population: Population | 'ACCIDENTS';
  url: string;
}

export function countLayers(base: string, map: PublicMap): CountLayer[] {
  return map.dataSources.flatMap((source): CountLayer[] => {
    if (source.types.includes('ACCIDENTS'))
      return [
        {
          sourceId: source.id,
          population: 'ACCIDENTS',
          url: `${base.replace(/\/$/, '')}/${encodeURIComponent(source.id)}_Accidents.geojson`,
        },
      ];
    return source.types.map((population) => ({
      sourceId: source.id,
      population,
      url: `${base.replace(/\/$/, '')}/${encodeURIComponent(source.id)}_TraficInfo${population === 'BICYCLES' ? '_BICYCLES' : ''}.geojson`,
    }));
  });
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function coordinates(value: unknown, depth: number): boolean {
  if (!Array.isArray(value)) return false;
  if (depth > 0)
    return (
      value.length > 0 && value.every((part) => coordinates(part, depth - 1))
    );
  return (
    value.length >= 2 &&
    value.every((part) => typeof part === 'number' && Number.isFinite(part)) &&
    Math.abs(value[0]) <= 180 &&
    Math.abs(value[1]) <= 90
  );
}

function geometry(value: unknown): boolean {
  if (!object(value)) return false;
  switch (value.type) {
    case 'Point':
      return coordinates(value.coordinates, 0);
    case 'MultiPoint':
    case 'LineString':
      return coordinates(value.coordinates, 1);
    case 'MultiLineString':
    case 'Polygon':
      return coordinates(value.coordinates, 2);
    case 'MultiPolygon':
      return coordinates(value.coordinates, 3);
    case 'GeometryCollection':
      return (
        Array.isArray(value.geometries) && value.geometries.every(geometry)
      );
    default:
      return false;
  }
}

export function parseCountData(value: unknown): CountData {
  if (
    !object(value) ||
    value.type !== 'FeatureCollection' ||
    !Array.isArray(value.features) ||
    !value.features.every(
      (feature) =>
        object(feature) &&
        feature.type === 'Feature' &&
        object(feature.properties) &&
        geometry(feature.geometry),
    )
  )
    throw new Error('GeoJSON de comptages incorrect');
  return value as unknown as CountData;
}

/** Cache successful downloads and share in-flight requests; failures remain retryable. */
export function createCountLoader(fetcher: typeof fetch = fetch) {
  const cache = new Map<string, Promise<CountData>>();
  return (url: string): Promise<CountData> => {
    const existing = cache.get(url);
    if (existing) return existing;
    const request = (async () => {
      const response = await fetcher(url);
      if (!response.ok) throw new Error(`HTTP ${response.status}: ${url}`);
      return parseCountData(await response.json());
    })();
    cache.set(url, request);
    void request.catch(() => cache.delete(url));
    return request;
  };
}
