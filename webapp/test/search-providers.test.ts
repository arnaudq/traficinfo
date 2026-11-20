import test from 'node:test';
import assert from 'node:assert/strict';
import { parseIgnResults, searchProviders } from '../src/search-providers.ts';

const place = {
  type: 'Feature',
  geometry: { type: 'Point', coordinates: [3.879638, 43.608525] },
  properties: { label: 'Place de la Comédie 34000 Montpellier' },
};

test('IGN adapter normalizes labels and longitude/latitude, ignoring unusable results', () => {
  assert.deepEqual(
    parseIgnResults({
      type: 'FeatureCollection',
      features: [
        place,
        null,
        {},
        { ...place, geometry: { type: 'Point', coordinates: [181, 43] } },
        { ...place, properties: { label: '' } },
      ],
    }),
    [{ label: place.properties.label, coordinates: [3.879638, 43.608525] }],
  );
  assert.deepEqual(
    parseIgnResults({ type: 'FeatureCollection', features: [] }),
    [],
  );
  assert.throws(() => parseIgnResults({}));
});

test('IGN searches encode queries, bias around map center and propagate cancellation and failures', async () => {
  const original = globalThis.fetch;
  const controller = new AbortController();
  globalThis.fetch = async (input, options) => {
    const url = new URL(String(input));
    assert.equal(
      url.origin + url.pathname,
      'https://data.geopf.fr/geocodage/search',
    );
    assert.equal(url.searchParams.get('q'), 'Comédie & Montpellier');
    assert.equal(url.searchParams.get('lon'), '3.8');
    assert.equal(url.searchParams.get('lat'), '43.6');
    assert.equal(url.searchParams.get('limit'), '5');
    assert.equal(options?.signal, controller.signal);
    return Response.json({ type: 'FeatureCollection', features: [place] });
  };
  try {
    assert.equal(
      (
        await searchProviders.ign.search(
          'Comédie & Montpellier',
          [3.8, 43.6],
          controller.signal,
        )
      ).length,
      1,
    );
    globalThis.fetch = async () => new Response('', { status: 429 });
    await assert.rejects(
      searchProviders.ign.search('Montpellier', [3, 43], controller.signal),
      /HTTP 429/,
    );
  } finally {
    globalThis.fetch = original;
  }
});
