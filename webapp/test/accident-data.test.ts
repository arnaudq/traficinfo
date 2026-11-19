import test from 'node:test';
import assert from 'node:assert/strict';
import {
  accidentColor,
  accidentLayer,
  accidentTooltip,
  accidentTotals,
} from '../src/accident-data.ts';

test('fatal layer requires at least one recorded death in any participant category', () => {
  assert.equal(
    accidentLayer({
      participantCounts: {
        CYCLIST: { HOSPITALIZED: 2 },
        PEDESTRIAN: { KILLED: 1 },
      },
    }),
    'FATAL_ACCIDENTS',
  );
  for (const properties of [
    { participantCounts: { CYCLIST: { KILLED: 0, HOSPITALIZED: 2 } } },
    { participantCounts: { UNKNOWN: { UNKNOWN: 1 } } },
    {},
  ])
    assert.equal(accidentLayer(properties), 'NON_FATAL_ACCIDENTS');
});

test('accident map severity sums all categories, counts people, and prioritizes fatalities', () => {
  const properties = {
    participantCounts: {
      PEDESTRIAN: { KILLED: 1, HOSPITALIZED: 2 },
      CYCLIST: { KILLED: 2, LIGHTLY_INJURED: 3, UNINJURED: 4, UNKNOWN: 1 },
    },
  };
  assert.deepEqual(accidentTotals(properties), {
    KILLED: 3,
    HOSPITALIZED: 2,
    LIGHTLY_INJURED: 3,
    UNINJURED: 4,
    UNKNOWN: 1,
  });
  assert.equal(accidentColor(properties), '#991b1b');
  assert.equal(accidentTooltip(properties), 'Accident : 3 tués, 5 blessés');
  assert.equal(
    accidentTooltip({
      participantCounts: { CYCLIST: { KILLED: 1, LIGHTLY_INJURED: 1 } },
    }),
    'Accident : 1 tué, 1 blessé',
  );
  assert.equal(
    accidentColor({ participantCounts: { CYCLIST: { HOSPITALIZED: 1 } } }),
    '#ea580c',
  );
  assert.equal(accidentColor({}), '#64748b');
});

test('missing or malformed casualty metadata does not produce invalid marker colors or counts', () => {
  const totals = accidentTotals({
    participantCounts: {
      BAD: { KILLED: '1', HOSPITALIZED: -1, LIGHTLY_INJURED: Infinity },
      EMPTY: null,
    },
  });
  assert.equal(
    Object.values(totals).reduce((a, b) => a + b, 0),
    0,
  );
});
