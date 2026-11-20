export interface SearchResult {
  label: string;
  coordinates: [longitude: number, latitude: number];
}

export interface SearchProvider {
  attribution: string;
  attributionUrl: string;
  search(
    query: string,
    center: [number, number],
    signal: AbortSignal,
  ): Promise<SearchResult[]>;
}

export function parseIgnResults(data: unknown): SearchResult[] {
  const collection = data as { type?: unknown; features?: unknown } | null;
  if (
    collection?.type !== 'FeatureCollection' ||
    !Array.isArray(collection.features)
  )
    throw new Error('Invalid IGN search response');
  return collection.features.flatMap((feature) => {
    const coordinates = feature?.geometry?.coordinates;
    const label = feature?.properties?.label;
    if (
      feature?.geometry?.type !== 'Point' ||
      !Array.isArray(coordinates) ||
      coordinates.length !== 2 ||
      !coordinates.every(
        (value: unknown) => typeof value === 'number' && Number.isFinite(value),
      ) ||
      Math.abs(coordinates[0]) > 180 ||
      Math.abs(coordinates[1]) > 90 ||
      typeof label !== 'string' ||
      !label.trim()
    )
      return [];
    return [
      {
        label,
        coordinates: [coordinates[0], coordinates[1]] as [number, number],
      },
    ];
  });
}

export const searchProviders = {
  ign: {
    attribution: 'Recherche © IGN / BAN',
    attributionUrl: 'https://cartes.gouv.fr/',
    async search(query, [longitude, latitude], signal) {
      const url = new URL('https://data.geopf.fr/geocodage/search');
      url.search = new URLSearchParams({
        q: query,
        limit: '5',
        lon: String(longitude),
        lat: String(latitude),
      }).toString();
      const response = await fetch(url, { signal });
      if (!response.ok) throw new Error(`IGN search: HTTP ${response.status}`);
      return parseIgnResults(await response.json());
    },
  },
} satisfies Record<string, SearchProvider>;

export type SearchProviderId = keyof typeof searchProviders;
