import type { Feature, Polygon } from 'geojson';
import type { BoundaryFeature, MapConfig } from '../src/types.ts';
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  boundaryBounds,
  boundaryUrl,
  mapsForBoundary,
} from '../src/territories.ts';
import { publicConfig, validateRegistry } from '../build/map-config.ts';

function registry(): MapConfig {
  return {
    $schema: './specs/mapconfig.schema.json',
    geoJsonBaseUrl: 'https://example.org/o',
    boundaries: {
      'departement:34': {
        boundaryId: 'departement:34',
        type: 'region',
        name: 'Hérault',
        geoJsonPath: 'boundaries/departements/34.geojson',
      },
      'epci:1': {
        boundaryId: 'epci:1',
        type: 'city',
        name: 'City A',
        parentId: 'departement:34',
        geoJsonPath: 'boundaries/epcis/1.geojson',
      },
      'epci:2': {
        boundaryId: 'epci:2',
        type: 'city',
        name: 'City B',
        parentId: 'departement:34',
        geoJsonPath: 'boundaries/epcis/2.geojson',
      },
    },
    maps: {
      A: {
        id: 'A',
        boundaryId: 'epci:1',
        name: 'Map A',
        description: 'Counts A',
        center: [3, 44],
        zoom: 10,
        dataSources: [{ id: 'SOURCE', types: ['VEHICULES'] }],
        properties: { secret: true },
        layerProperties: {},
      },
      B: {
        id: 'B',
        boundaryId: 'epci:2',
        name: 'Map B',
        description: 'Counts B',
        center: [3, 44],
        zoom: 10,
        dataSources: [{ id: 'SOURCE', types: ['VEHICULES'] }],
      },
    },
  };
}

function feature(id: string): Feature<Polygon, { boundaryId: string }> {
  return {
    type: 'Feature',
    properties: { boundaryId: id },
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [2, 43],
          [4, 43],
          [4, 45],
          [2, 43],
        ],
      ],
    },
  };
}

test('Region selection includes several City maps and its own optional map', () => {
  const config = registry();
  assert.deepEqual(
    mapsForBoundary(config, 'departement:34').map((map) => map.id),
    ['A', 'B'],
  );
  config.maps.R = { ...config.maps.A, id: 'R', boundaryId: 'departement:34' };
  config.maps.A2 = { ...config.maps.A, id: 'A2' };
  assert.deepEqual(
    mapsForBoundary(config, 'departement:34').map((map) => map.id),
    ['A', 'B', 'R', 'A2'],
  );
  assert.deepEqual(
    mapsForBoundary(config, 'epci:1').map((map) => map.id),
    ['A', 'A2'],
  );
});

test('base URL joins retain the object directory with or without a trailing slash', () => {
  for (const base of ['https://example.org/o', 'https://example.org/o/'])
    assert.equal(
      boundaryUrl(base, 'boundaries/epcis/1.geojson'),
      'https://example.org/o/boundaries/epcis/1.geojson',
    );
});

test('bounds cover all polygons and invalid or mismatched geometry is rejected', () => {
  const single = feature('epci:1');
  assert.deepEqual(boundaryBounds(single, 'epci:1'), [2, 43, 4, 45]);
  const multi: BoundaryFeature = structuredClone(single);
  multi.geometry = {
    type: 'MultiPolygon',
    coordinates: [
      single.geometry.coordinates,
      [
        [
          [0, 40],
          [1, 40],
          [1, 41],
          [0, 40],
        ],
      ],
    ],
  };
  assert.deepEqual(boundaryBounds(multi, 'epci:1'), [0, 40, 4, 45]);
  assert.throws(() => boundaryBounds(single, 'other'));
  single.geometry.coordinates[0].pop();
  assert.throws(() => boundaryBounds(single, 'epci:1'));
});

test('public config derives bounds without exposing uMap overrides or mutating source', async () => {
  const config = registry();
  config.maps.A.searchProvider = 'ign';
  config.maps.A.path = 'Montpellier';
  const output = await publicConfig(config, async (boundary) =>
    feature(boundary.boundaryId),
  );
  assert.deepEqual(output.boundaries['epci:1'].bounds, [2, 43, 4, 45]);
  assert.equal(output.maps.A.searchProvider, 'ign');
  assert.equal(output.maps.A.path, 'Montpellier');
  assert.equal('$schema' in output, false);
  assert.equal('properties' in output.maps.A, false);
  assert.equal('layerProperties' in output.maps.A, false);
  assert.equal('dataSources' in output.boundaries['epci:1'], false);
  assert.equal('bounds' in config.boundaries['epci:1'], false);
  assert.deepEqual(output.maps.A.dataSources, config.maps.A.dataSources);
  await assert.rejects(publicConfig(config, async () => feature('wrong')));
  await assert.rejects(
    publicConfig(config, async () => {
      throw new Error('Missing file');
    }),
  );
});

test('registry validation rejects broken references, unsupported fields and invalid selections', () => {
  const sources = { SOURCE: { id: 'SOURCE' } };
  validateRegistry(registry(), sources);
  const explicitProvider = registry();
  explicitProvider.maps.A.searchProvider = 'ign';
  validateRegistry(explicitProvider, sources);
  const changes: Array<(config: MapConfig) => void> = [
    (config) => {
      Object.assign(config.maps.A, { searchProvider: 'unknown' });
    },
    (config) => {
      config.boundaries['epci:1'].boundaryId = 'wrong';
    },
    (config) => {
      config.boundaries['epci:1'].parentId = 'epci:2';
    },
    (config) => {
      Object.assign(config.boundaries['epci:1'], { type: 'epci' });
    },
    (config) => {
      Object.assign(config.boundaries['epci:1'], { style: {} });
    },
    (config) => {
      config.maps.A.boundaryId = 'missing';
    },
    (config) => {
      config.maps.A.dataSources[0].id = 'missing';
    },
    (config) => {
      Object.assign(config.maps.A.dataSources[0], { types: ['unknown'] });
    },
  ];
  for (const change of changes) {
    const config = registry();
    change(config);
    assert.throws(() => validateRegistry(config, sources));
  }
});

test('accident datasets are public map selections independent of the count registry', () => {
  const sources = { SOURCE: { id: 'SOURCE' } };
  const config = registry();
  config.maps.A.dataSources.push({ id: 'ONISR_DPT34', types: ['ACCIDENTS'] });
  validateRegistry(config, sources);
  config.maps.A.dataSources.push({ id: 'ONISR_DPT34', types: ['ACCIDENTS'] });
  assert.throws(() => validateRegistry(config, sources));
  config.maps.A.dataSources.pop();
  Object.assign(config.maps.A.dataSources.at(-1)!, {
    types: ['ACCIDENTS', 'BICYCLES'],
  });
  assert.throws(() => validateRegistry(config, sources));
});

test('map paths reject ambiguous IDs, invalid segments and reserved directories', () => {
  const sources = { SOURCE: { id: 'SOURCE' } };
  for (const path of [
    '',
    '/Montpellier',
    'two/parts',
    '..',
    'a?b',
    'a#b',
    'a b',
    'assets',
    'licenses',
    'src',
    'B',
    null,
    123,
  ]) {
    const config = registry();
    Object.assign(config.maps.A, { path });
    assert.throws(() => validateRegistry(config, sources));
  }
  const config = registry();
  config.maps.A.path = 'Montpellier';
  validateRegistry(config, sources);
  config.maps.B.path = 'Montpellier';
  assert.throws(() => validateRegistry(config, sources), /Duplicate map path/);
  config.maps.B.path = 'B';
  validateRegistry(config, sources);
});
