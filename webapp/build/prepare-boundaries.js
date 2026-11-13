import { mkdir, writeFile } from 'node:fs/promises';
import { readRegistry, preparedDirectory } from './map-config.js';
import { boundaryBounds, boundaryUrl } from '../src/territories.js';

const config = await readRegistry();
await Promise.all(
  Object.values(config.boundaries).map(async (boundary) => {
    const url = boundaryUrl(config.geoJsonBaseUrl, boundary.geoJsonPath);
    const response = await fetch(url, { signal: AbortSignal.timeout(30000) });
    if (!response.ok)
      throw new Error(`Cannot download ${url}: HTTP ${response.status}`);
    const feature = await response.json();
    boundaryBounds(feature, boundary.boundaryId);
    const destination = new URL(boundary.geoJsonPath, preparedDirectory);
    await mkdir(new URL('./', destination), { recursive: true });
    await writeFile(destination, JSON.stringify(feature));
    console.log(`Prepared ${boundary.boundaryId}`);
  }),
);
