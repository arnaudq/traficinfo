import test from 'node:test';
import assert from 'node:assert/strict';
import {
  counterDetailsUrl,
  counterOverview,
  createHistoryLoader,
  directionKey,
  historySamples,
  historySelection,
  historyUrl,
  parseHistory,
} from '../src/history-data.ts';
import type { CounterHistory, HistoryCount } from '../src/history-data.ts';

function count(overrides: Partial<HistoryCount> = {}): HistoryCount {
  return {
    id: 'combined',
    population: 'VEHICULES',
    directionType: 'BIDIRECTIONAL',
    direction: null,
    dailyAverage: 100,
    dailyAverageBasis: 'ANNUAL',
    morningPeakAverage: 12,
    morningPeakText: '12 (7h - 8h)',
    eveningPeakAverage: null,
    eveningPeakText: null,
    v85Speed: null,
    meanSpeed: null,
    coverage: null,
    ...overrides,
  };
}
function publication(): CounterHistory {
  return {
    schemaVersion: 1,
    dataSourceId: 'MMM',
    sourceRevision: 'r1',
    publishedAt: '2026-11-22T12:00:00Z',
    datasets: { archive: { url: 'https://example.org/dataset' } },
    counters: {
      '001': {
        codeCentrale: '001',
        displayName: 'Rue de la République',
        cityName: 'Montpellier',
        roadReference: null,
        osmWayId: null,
        campaigns: [
          {
            id: '2024-main',
            year: 2024,
            startDate: null,
            endDate: null,
            label: null,
            datasetId: 'archive',
            countingMode: 'PERMANENT',
            counts: [count({ dailyAverage: 0 })],
          },
          {
            id: '2026-spring',
            year: 2026,
            startDate: '2026-03-01',
            endDate: '2026-03-07',
            label: 'Printemps',
            datasetId: 'archive',
            countingMode: 'TEMPORARY',
            counts: [
              count(),
              count({
                id: '1',
                directionType: 'UNIDIRECTIONAL',
                direction: { id: '1', label: 'Vers le centre' },
                dailyAverage: 40,
              }),
              count({
                id: 'bicycles',
                population: 'BICYCLES',
                dailyAverage: 20,
              }),
            ],
          },
          {
            id: '2026-autumn',
            year: 2026,
            startDate: '2026-10-01',
            endDate: '2026-10-07',
            label: 'Automne',
            datasetId: 'archive',
            countingMode: 'TEMPORARY',
            counts: [count({ dailyAverage: null })],
          },
        ],
      },
    },
  };
}

test('canonical history preserves leading-zero IDs, zero, missing measures and distinct campaigns', () => {
  const data = publication();
  assert.equal(parseHistory(data, 'MMM'), data);
  const samples = historySamples(
    data.counters['001'],
    'VEHICULES',
    directionKey(count()),
  );
  assert.deepEqual(
    samples.map((sample) => sample.campaign.year),
    [2024, 2026, 2026],
  );
  assert.deepEqual(
    samples.map((sample) => sample.count.dailyAverage),
    [0, 100, null],
  );
  assert.equal(samples[1].count.morningPeakText, '12 (7h - 8h)');
  assert.equal(
    historySamples(
      data.counters['001'],
      'VEHICULES',
      directionKey(count()),
      2026,
    ).length,
    2,
  );
  assert.deepEqual(
    historySamples(data.counters['001'], 'BICYCLES', directionKey(count())).map(
      (sample) => sample.count.dailyAverage,
    ),
    [20],
  );
});

test('direction selection never adds components to combined counts', () => {
  const counter = publication().counters['001'];
  assert.equal(
    historySamples(
      counter,
      'VEHICULES',
      JSON.stringify(['UNIDIRECTIONAL', '1']),
    )[0].count.dailyAverage,
    40,
  );
  assert.equal(
    historySamples(counter, 'VEHICULES', directionKey(count()), 2026)[0].count
      .dailyAverage,
    100,
  );
  assert.equal(historySamples(counter, 'VEHICULES', 'missing').length, 0);
});

test('invalid schemas, source identities, measurements and campaign references are rejected', () => {
  const changes: ((data: CounterHistory) => void)[] = [
    (data) => {
      data.schemaVersion = 2 as 1;
    },
    (data) => {
      data.dataSourceId = 'NIM';
    },
    (data) => {
      data.publishedAt = 'not-a-date';
    },
    (data) => {
      data.counters['001'].codeCentrale = '1';
    },
    (data) => {
      data.counters['001'].campaigns[0].datasetId = 'absent';
    },
    (data) => {
      data.counters['001'].campaigns[0].counts[0].dailyAverage = -1;
    },
    (data) => {
      data.counters['001'].campaigns[0].counts[0].meanSpeed = 1.5;
    },
    (data) => {
      data.counters['001'].campaigns[0].countingMode =
        'DISREGARDED' as 'PERMANENT';
    },
    (data) => {
      data.counters['001'].campaigns.push(data.counters['001'].campaigns[0]);
    },
    (data) => {
      data.counters['001'].campaigns[0].counts.push(count());
    },
  ];
  for (const change of changes) {
    const data = publication();
    change(data);
    assert.throws(() => parseHistory(data, 'MMM'), /incorrect/);
  }
  for (const value of [null, [], {}, { ...publication(), counters: null }])
    assert.throws(() => parseHistory(value, 'MMM'));
});

test('coverage validation preserves incomplete observed days independently of counting mode', () => {
  const data = publication();
  data.counters['001'].campaigns[0].counts[0].coverage = {
    firstDate: '2024-01-01',
    lastDate: '2024-01-02',
    observedDays: 2,
    completeDays: 0,
    observedHours: 23,
    conflictingHours: 1,
  };
  assert.equal(parseHistory(data, 'MMM'), data);
  data.counters['001'].campaigns[0].counts[0].coverage!.completeDays = -1;
  assert.throws(() => parseHistory(data, 'MMM'));
});

test('empty publications are valid and inherited object properties are not counters', () => {
  const data = publication();
  data.counters = {};
  assert.equal(parseHistory(data, 'MMM'), data);
  assert.equal(Object.hasOwn(data.counters, 'constructor'), false);
});

test('popup URLs round-trip arbitrary counter IDs under root and project hosting', () => {
  for (const prefix of ['/', '/traficinfo/']) {
    const selection = {
      source: 'MMM',
      id: '001/à &?#',
      population: 'BICYCLES' as const,
      mapId: 'NIM',
    };
    const url = counterDetailsUrl(
      new URL(prefix, 'https://example.org'),
      selection,
    );
    assert.equal(url.pathname, `${prefix}counter.html`);
    assert.deepEqual(historySelection(url), selection);
  }
  assert.equal(
    historyUrl('https://example.org/o/', 'MMM'),
    'https://example.org/o/history/MMM.json',
  );
  assert.throws(() => historyUrl('https://example.org/o', '../secret'));
  for (const query of [
    '',
    '?source=MMM',
    '?source=../x&id=1',
    '?source=MMM&id=1&type=ACCIDENTS',
  ])
    assert.throws(() =>
      historySelection(new URL(`https://example.org/counter.html${query}`)),
    );
});

test('one download per source is shared across concurrent loads, counter visits and filter changes', async () => {
  let requests = 0;
  let release!: (response: Response) => void;
  const load = createHistoryLoader(async () => {
    requests += 1;
    return new Promise<Response>((resolve) => {
      release = resolve;
    });
  });
  const first = load('https://example.org/o', 'MMM');
  const second = load('https://example.org/o/', 'MMM');
  assert.equal(first, second);
  release(Response.json(publication()));
  const history = await first;
  historySamples(
    history.counters['001'],
    'VEHICULES',
    directionKey(count()),
    2024,
  );
  await load('https://example.org/o', 'MMM');
  assert.equal(requests, 1);
});

test('HTTP and schema failures remain retryable', async () => {
  let requests = 0;
  const load = createHistoryLoader(async () => {
    requests += 1;
    if (requests === 1) return new Response('', { status: 404 });
    return Response.json(requests === 2 ? {} : publication());
  });
  await assert.rejects(load('https://example.org/o', 'MMM'), /HTTP 404/);
  await assert.rejects(load('https://example.org/o', 'MMM'), /incorrect/);
  assert.equal(
    (await load('https://example.org/o', 'MMM')).dataSourceId,
    'MMM',
  );
  assert.equal(requests, 3);
});

test('counter overview uses the full range across populations and directions independently of filters', () => {
  const counter = publication().counters['001'];
  counter.campaigns.push({
    ...counter.campaigns[0],
    id: 'old-bike-only',
    year: 2011,
    counts: [count({ population: 'BICYCLES' })],
  });
  counter.campaigns.push({
    ...counter.campaigns[0],
    id: 'empty',
    year: 2001,
    counts: [],
  });
  assert.equal(
    historySamples(counter, 'VEHICULES', directionKey(count()), 2026).length,
    2,
  );
  assert.deepEqual(counterOverview(counter), {
    range: '2011–2026',
    countingMode: 'Permanent et temporaire selon les campagnes',
  });
});

test('counter mode distinguishes permanent, temporary, unknown and partially known history', () => {
  const counter = publication().counters['001'];
  for (const campaign of counter.campaigns) campaign.countingMode = 'PERMANENT';
  assert.equal(counterOverview(counter).countingMode, 'Permanent');
  counter.campaigns[0].countingMode = 'UNKNOWN';
  assert.equal(
    counterOverview(counter).countingMode,
    'Permanent (non renseigné pour certaines campagnes)',
  );
  for (const campaign of counter.campaigns) campaign.countingMode = 'TEMPORARY';
  assert.equal(counterOverview(counter).countingMode, 'Temporaire');
  for (const campaign of counter.campaigns) campaign.countingMode = 'UNKNOWN';
  assert.equal(counterOverview(counter).countingMode, 'Non renseigné');
  counter.campaigns = [counter.campaigns[0]];
  assert.equal(counterOverview(counter).range, '2024');
  counter.campaigns = [];
  assert.deepEqual(counterOverview(counter), {
    range: null,
    countingMode: 'Non renseigné',
  });
});
