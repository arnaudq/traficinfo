import type { CounterProperties } from './count-data.ts';

const severities = [
  'KILLED',
  'HOSPITALIZED',
  'LIGHTLY_INJURED',
  'UNINJURED',
  'UNKNOWN',
] as const;
export type Severity = (typeof severities)[number];

export type AccidentLayer = 'FATAL_ACCIDENTS' | 'NON_FATAL_ACCIDENTS';

/** Fatal means at least one recorded death; unknown severities do not imply death. */
export function accidentLayer(properties: CounterProperties): AccidentLayer {
  return accidentTotals(properties).KILLED > 0
    ? 'FATAL_ACCIDENTS'
    : 'NON_FATAL_ACCIDENTS';
}

/** Counts in the published projection are people, not accident counts. */
export function accidentTotals(
  properties: CounterProperties,
): Record<Severity, number> {
  const totals = Object.fromEntries(
    severities.map((key) => [key, 0]),
  ) as Record<Severity, number>;
  const counts = properties.participantCounts;
  if (counts === null || typeof counts !== 'object' || Array.isArray(counts))
    return totals;
  for (const category of Object.values(counts)) {
    if (
      category === null ||
      typeof category !== 'object' ||
      Array.isArray(category)
    )
      continue;
    for (const severity of severities) {
      const value: unknown = (category as Record<string, unknown>)[severity];
      if (
        typeof value === 'number' &&
        Number.isSafeInteger(value) &&
        value >= 0
      )
        totals[severity] += value;
    }
  }
  return totals;
}

export function accidentColor(properties: CounterProperties): string {
  const totals = accidentTotals(properties);
  return totals.KILLED > 0
    ? '#991b1b'
    : totals.HOSPITALIZED + totals.LIGHTLY_INJURED > 0
      ? '#ea580c'
      : '#64748b';
}

export function accidentTooltip(properties: CounterProperties): string {
  const totals = accidentTotals(properties);
  const killed = totals.KILLED;
  const injured = totals.HOSPITALIZED + totals.LIGHTLY_INJURED;
  return `Accident : ${killed} tué${killed > 1 ? 's' : ''}, ${injured} blessé${injured > 1 ? 's' : ''}`;
}
