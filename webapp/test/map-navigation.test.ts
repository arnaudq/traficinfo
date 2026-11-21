import test from 'node:test';
import assert from 'node:assert/strict';
import { mapAtUrl, mapRoutes, mapUrl } from '../src/map-navigation.ts';
import type { PublicMap } from '../src/types.ts';

const montpellier: PublicMap = {
  id: 'MMM',
  path: 'Montpellier',
  name: 'Montpellier',
  description: 'Counts',
  boundaryId: 'epci:1',
  center: [3, 44],
  zoom: 10,
  dataSources: [{ id: 'MMM', types: ['VEHICULES'] }],
};
const nimes: PublicMap = { ...montpellier, id: 'NIM', path: undefined };
const routes = mapRoutes({ MMM: montpellier, NIM: nimes });

for (const prefix of ['/', '/traficinfo/']) {
  test(`direct links and navigation resolve under ${prefix}`, () => {
    const root = new URL(prefix, 'https://example.org');
    for (const path of ['MMM', 'MMM/', 'Montpellier', 'Montpellier/?q=1#map']) {
      assert.equal(mapAtUrl(routes, root, new URL(path, root)), montpellier);
    }
    assert.equal(mapAtUrl(routes, root, new URL('NIM/', root)), nimes);
    for (const path of [
      '',
      'index.html',
      'missing',
      'MMM/other',
      'montpellier',
    ]) {
      assert.equal(mapAtUrl(routes, root, new URL(path, root)), undefined);
    }
    assert.equal(
      mapAtUrl(routes, root, new URL('https://other.org/MMM/')),
      undefined,
    );
    assert.equal(mapUrl(root, montpellier).pathname, `${prefix}Montpellier/`);
    assert.equal(mapUrl(root, nimes).pathname, `${prefix}NIM/`);
    assert.equal(mapUrl(root).href, root.href);
  });
}

test('project routing does not match paths outside its base directory', () => {
  assert.equal(
    mapAtUrl(
      routes,
      new URL('https://example.org/traficinfo/'),
      new URL('https://example.org/MMM/'),
    ),
    undefined,
  );
});
