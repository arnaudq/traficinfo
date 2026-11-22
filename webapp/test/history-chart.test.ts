import test from 'node:test';
import assert from 'node:assert/strict';
import { historyChartLayout } from '../src/history-chart.ts';
import type { HistorySample } from '../src/history-data.ts';

function sample(
  year: number,
  average: number | null,
  mean: number | null,
  v85: number | null,
  id = String(year),
): HistorySample {
  return {
    campaign: {
      id,
      year,
      startDate: null,
      endDate: null,
      label: null,
      datasetId: 'source',
      countingMode: 'PERMANENT',
      counts: [],
    },
    count: {
      id: 'combined',
      population: 'VEHICULES',
      directionType: 'BIDIRECTIONAL',
      direction: null,
      dailyAverage: average,
      dailyAverageBasis: 'ANNUAL',
      morningPeakAverage: null,
      morningPeakText: null,
      eveningPeakAverage: null,
      eveningPeakText: null,
      meanSpeed: mean,
      v85Speed: v85,
      coverage: null,
    },
  };
}

test('year axis uses elapsed years rather than collapsing gaps between campaigns', () => {
  const layout = historyChartLayout([
    sample(2013, 200, 40, 60),
    sample(2011, 100, 30, 50),
  ]);
  assert.deepEqual(
    layout.years.map((item) => item.year),
    [2011, 2012, 2013],
  );
  assert.ok(
    Math.abs(
      layout.years[1].x -
        layout.years[0].x -
        (layout.years[2].x - layout.years[1].x),
    ) < 1e-9,
  );
  assert.ok(layout.bars[0].x < layout.bars[1].x);
  assert.equal(layout.meanSpeed.length, 2);
  assert.equal(layout.v85Speed.length, 2);
});

test('count bars and speed lines use separate scales and keep zero distinct from missing measurements', () => {
  const layout = historyChartLayout([
    sample(2024, 0, 0, null),
    sample(2025, null, null, 75),
    sample(2026, 27501, 40, 70),
  ]);
  assert.deepEqual(
    layout.bars.map((bar) => bar.value),
    [0, 27501],
  );
  assert.equal(layout.bars[0].height, 0);
  assert.equal(layout.missingCounts.length, 1);
  assert.equal(layout.countMaximum, 40000);
  assert.equal(layout.speedMaximum, 80);
  assert.equal(layout.meanSpeed.length, 2);
  assert.equal(layout.meanSpeed[0][0].value, 0);
  assert.equal(layout.v85Speed.length, 1);
  assert.equal(layout.v85Speed[0].length, 2);
});

test('multiple campaigns per year remain side by side without averaging counts or speeds', () => {
  const layout = historyChartLayout([
    sample(2025, 20, 40, 60, 'b'),
    sample(2025, 10, 30, 50, 'a'),
  ]);
  assert.equal(layout.years.length, 1);
  assert.deepEqual(
    layout.bars.map((bar) => bar.value),
    [10, 20],
  );
  assert.ok(layout.bars[0].x < layout.bars[1].x);
  assert.equal(layout.meanSpeed.length, 1);
  assert.deepEqual(
    layout.meanSpeed[0].map((point) => point.value),
    [30, 40],
  );
  for (const bar of layout.bars)
    assert.ok(bar.x >= layout.left && bar.x <= layout.right);
});

test('empty and all-missing plots have finite scales and no invented speed lines', () => {
  for (const samples of [[], [sample(2025, null, null, null)]]) {
    const layout = historyChartLayout(samples);
    assert.ok(Number.isFinite(layout.countMaximum));
    assert.ok(Number.isFinite(layout.speedMaximum));
    assert.equal(layout.bars.length, 0);
    assert.deepEqual(layout.meanSpeed, []);
    assert.deepEqual(layout.v85Speed, []);
  }
});
