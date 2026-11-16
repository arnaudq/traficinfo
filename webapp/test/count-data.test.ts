import test from 'node:test';
import assert from 'node:assert/strict';
import {
  countLayers,
  createCountLoader,
  parseCountData,
} from '../src/count-data.ts';
import type { PublicMap } from '../src/types.ts';

const data = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      properties: { moyenne_jour_annuelle: 0 },
      geometry: { type: 'Point', coordinates: [3.8, 43.6] },
    },
  ],
};

test('map selections produce only the declared population URLs, in order', () => {
  const map = {
    dataSources: [
      { id: 'MMM', types: ['VEHICULES', 'BICYCLES'] },
      { id: 'DPT34', types: ['VEHICULES'] },
    ],
  } as PublicMap;
  assert.deepEqual(
    countLayers('https://example.org/o/', map).map((layer) => layer.url),
    [
      'https://example.org/o/MMM_TraficInfo.geojson',
      'https://example.org/o/MMM_TraficInfo_BICYCLES.geojson',
      'https://example.org/o/DPT34_TraficInfo.geojson',
    ],
  );
});

test('validate count data, including empty populations and zero averages', () => {
  assert.equal(parseCountData(data), data);
  assert.deepEqual(
    parseCountData({ type: 'FeatureCollection', features: [] }).features,
    [],
  );
  for (const geometry of [
    null,
    { type: 'Point', coordinates: [200, 43] },
    { type: 'Point', coordinates: ['3', 43] },
    { type: 'Other' },
  ]) {
    assert.throws(() =>
      parseCountData({
        ...data,
        features: [{ ...data.features[0], geometry }],
      }),
    );
  }
  assert.throws(() =>
    parseCountData({ type: 'FeatureCollection', features: [null] }),
  );
});

test('concurrent downloads are shared and successful responses cached', async () => {
  let requests = 0;
  let release!: (response: Response) => void;
  const load = createCountLoader(async () => {
    requests += 1;
    return new Promise<Response>((resolve) => {
      release = resolve;
    });
  });
  const first = load('https://example.org/counts');
  const second = load('https://example.org/counts');
  assert.equal(first, second);
  release(Response.json(data));
  assert.deepEqual(await first, data);
  await load('https://example.org/counts');
  assert.equal(requests, 1);
});

test('HTTP and invalid-data failures can be retried', async () => {
  let requests = 0;
  const load = createCountLoader(async () => {
    requests += 1;
    if (requests === 1) return new Response('', { status: 503 });
    return Response.json(requests === 2 ? {} : data);
  });
  await assert.rejects(load('counts'), /HTTP 503/);
  await assert.rejects(load('counts'), /GeoJSON/);
  assert.deepEqual(await load('counts'), data);
  assert.equal(requests, 3);
});
