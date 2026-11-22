/** Public canonical history as exported by the parsers module. */
export type HistoryPopulation =
  'VEHICULES' | 'BICYCLES' | 'PIETONS_VELOS' | 'UNKNOWN';
export type DirectionType = 'UNIDIRECTIONAL' | 'BIDIRECTIONAL' | 'UNKNOWN';
export type CountingMode = 'PERMANENT' | 'TEMPORARY' | 'UNKNOWN';
export type AverageBasis =
  'ANNUAL' | 'CAMPAIGN' | 'OBSERVED_COMPLETE_DAYS' | 'UNKNOWN';

export interface HistoryCount {
  id: string;
  population: HistoryPopulation;
  directionType: DirectionType;
  direction: { id: string; label: string | null } | null;
  dailyAverage: number | null;
  dailyAverageBasis: AverageBasis;
  morningPeakAverage: number | null;
  morningPeakText: string | null;
  eveningPeakAverage: number | null;
  eveningPeakText: string | null;
  v85Speed: number | null;
  meanSpeed: number | null;
  coverage: {
    firstDate: string | null;
    lastDate: string | null;
    observedDays: number | null;
    completeDays: number | null;
    observedHours: number | null;
    conflictingHours: number | null;
  } | null;
}

export interface HistoryCampaign {
  id: string;
  year: number;
  startDate: string | null;
  endDate: string | null;
  label: string | null;
  datasetId: string;
  countingMode: CountingMode;
  counts: HistoryCount[];
}

export interface HistoryCounter {
  codeCentrale: string;
  displayName: string | null;
  cityName: string | null;
  roadReference: string | null;
  osmWayId: string | null;
  campaigns: HistoryCampaign[];
}

export interface CounterHistory {
  schemaVersion: 1;
  dataSourceId: string;
  sourceRevision: string;
  publishedAt: string;
  datasets: Record<string, { url: string }>;
  counters: Record<string, HistoryCounter>;
}

export interface HistorySelection {
  source: string;
  id: string;
  population?: HistoryPopulation;
  mapId?: string;
}

export interface HistorySample {
  campaign: HistoryCampaign;
  count: HistoryCount;
}

export const populationLabels: Record<HistoryPopulation, string> = {
  VEHICULES: 'Véhicules motorisés',
  BICYCLES: 'Vélos',
  PIETONS_VELOS: 'Piétons et vélos',
  UNKNOWN: 'Population non renseignée',
};

export const modeLabels: Record<CountingMode, string> = {
  PERMANENT: 'Comptage permanent',
  TEMPORARY: 'Comptage temporaire',
  UNKNOWN: 'Mode de comptage non renseigné',
};

export const basisLabels: Record<AverageBasis, string> = {
  ANNUAL: 'Moyenne journalière annuelle',
  CAMPAIGN: 'Moyenne journalière de campagne',
  OBSERVED_COMPLETE_DAYS: 'Moyenne sur les jours complets observés',
  UNKNOWN: 'Base de calcul non renseignée',
};

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function string(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}
function nullableText(value: unknown): boolean {
  return value === null || typeof value === 'string';
}
function integer(value: unknown): boolean {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 0 &&
    value <= 2147483647
  );
}
function nullableInteger(value: unknown): boolean {
  return value === null || integer(value);
}
function member<T extends string>(
  value: unknown,
  choices: Record<T, string>,
): value is T {
  return typeof value === 'string' && Object.hasOwn(choices, value);
}
function date(value: unknown): boolean {
  return (
    value === null ||
    (typeof value === 'string' &&
      /^\d{4}-\d{2}-\d{2}$/.test(value) &&
      Number.isFinite(Date.parse(value)))
  );
}
function count(value: unknown): boolean {
  if (!object(value)) return false;
  const coverage = value.coverage;
  return (
    string(value.id) &&
    member(value.population, populationLabels) &&
    ['UNIDIRECTIONAL', 'BIDIRECTIONAL', 'UNKNOWN'].includes(
      String(value.directionType),
    ) &&
    (value.direction === null ||
      (object(value.direction) &&
        string(value.direction.id) &&
        nullableText(value.direction.label))) &&
    [
      'dailyAverage',
      'morningPeakAverage',
      'eveningPeakAverage',
      'v85Speed',
      'meanSpeed',
    ].every((key) => nullableInteger(value[key])) &&
    nullableText(value.morningPeakText) &&
    nullableText(value.eveningPeakText) &&
    member(value.dailyAverageBasis, basisLabels) &&
    (coverage === null ||
      (object(coverage) &&
        date(coverage.firstDate) &&
        date(coverage.lastDate) &&
        [
          'observedDays',
          'completeDays',
          'observedHours',
          'conflictingHours',
        ].every((key) => nullableInteger(coverage[key]))))
  );
}

/** Reject unsupported publications before displaying measurements. */
export function parseHistory(value: unknown, sourceId: string): CounterHistory {
  const invalid = () => {
    throw new Error('Historique de comptages incorrect');
  };
  if (
    !object(value) ||
    value.schemaVersion !== 1 ||
    value.dataSourceId !== sourceId ||
    !string(value.sourceRevision) ||
    !string(value.publishedAt) ||
    !Number.isFinite(Date.parse(value.publishedAt)) ||
    !object(value.datasets) ||
    !object(value.counters)
  )
    return invalid();
  const datasets = value.datasets;
  if (
    !Object.values(datasets).every(
      (dataset) => object(dataset) && string(dataset.url),
    )
  )
    return invalid();
  for (const [id, counter] of Object.entries(value.counters)) {
    if (
      !object(counter) ||
      !string(id) ||
      counter.codeCentrale !== id ||
      !['displayName', 'cityName', 'roadReference', 'osmWayId'].every((key) =>
        nullableText(counter[key]),
      ) ||
      !Array.isArray(counter.campaigns)
    )
      return invalid();
    const campaignIds = new Set<string>();
    for (const campaign of counter.campaigns) {
      if (
        !object(campaign) ||
        !string(campaign.id) ||
        campaignIds.has(campaign.id) ||
        !integer(campaign.year) ||
        (campaign.year as number) < 1 ||
        (campaign.year as number) > 9999 ||
        !date(campaign.startDate) ||
        !date(campaign.endDate) ||
        !nullableText(campaign.label) ||
        !string(campaign.datasetId) ||
        !Object.hasOwn(datasets, campaign.datasetId) ||
        !member(campaign.countingMode, modeLabels) ||
        !Array.isArray(campaign.counts) ||
        !campaign.counts.every(count)
      )
        return invalid();
      campaignIds.add(campaign.id);
      const ids = campaign.counts.map((item: HistoryCount) => item.id);
      if (new Set(ids).size !== ids.length) return invalid();
    }
  }
  return value as unknown as CounterHistory;
}

export function historyUrl(base: string, sourceId: string): string {
  if (!/^[A-Z][A-Z0-9_]*$/.test(sourceId))
    throw new Error('Source de comptages incorrecte');
  return `${base.replace(/\/$/, '')}/history/${encodeURIComponent(sourceId)}.json`;
}

export function historySelection(url: URL): HistorySelection {
  const source = url.searchParams.get('source');
  const id = url.searchParams.get('id');
  const population = url.searchParams.get('type');
  if (!source || !/^[A-Z][A-Z0-9_]*$/.test(source) || !id || !id.trim())
    throw new Error(
      'Le lien doit préciser une source et un identifiant de compteur.',
    );
  if (population !== null && !member(population, populationLabels))
    throw new Error('La population demandée est incorrecte.');
  return {
    source,
    id,
    population: population ?? undefined,
    mapId: url.searchParams.get('map') ?? undefined,
  };
}

export function counterDetailsUrl(root: URL, selection: HistorySelection): URL {
  const url = new URL('counter.html', root);
  url.searchParams.set('source', selection.source);
  url.searchParams.set('id', selection.id);
  if (selection.population) url.searchParams.set('type', selection.population);
  if (selection.mapId) url.searchParams.set('map', selection.mapId);
  return url;
}

/** A single source download serves every counter and filter in this page session. */
export function createHistoryLoader(fetcher: typeof fetch = fetch) {
  const cache = new Map<string, Promise<CounterHistory>>();
  return (base: string, source: string): Promise<CounterHistory> => {
    const url = historyUrl(base, source);
    const existing = cache.get(url);
    if (existing) return existing;
    const request = (async () => {
      const response = await fetcher(url);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return parseHistory(await response.json(), source);
    })();
    cache.set(url, request);
    void request.catch(() => cache.delete(url));
    return request;
  };
}

/** Coverage and population identify a series; a combined count is never added to its components. */
export function directionKey(count: HistoryCount): string {
  return JSON.stringify([count.directionType, count.direction?.id ?? null]);
}
export function directionLabel(count: HistoryCount): string {
  if (count.directionType === 'BIDIRECTIONAL') return 'Sens cumulés';
  return (
    count.direction?.label ??
    (count.direction
      ? `Sens ${count.direction.id}`
      : count.directionType === 'UNIDIRECTIONAL'
        ? 'Un sens'
        : 'Sens non renseigné')
  );
}
export function historySamples(
  counter: HistoryCounter,
  population: HistoryPopulation,
  direction: string,
  year?: number,
): HistorySample[] {
  return counter.campaigns
    .flatMap((campaign) =>
      campaign.year === year || year === undefined
        ? campaign.counts
            .filter(
              (count) =>
                count.population === population &&
                directionKey(count) === direction,
            )
            .map((count) => ({ campaign, count }))
        : [],
    )
    .sort(
      (a, b) =>
        a.campaign.year - b.campaign.year ||
        (a.campaign.startDate ?? '').localeCompare(
          b.campaign.startDate ?? '',
        ) ||
        a.campaign.id.localeCompare(b.campaign.id) ||
        a.count.id.localeCompare(b.count.id),
    );
}

/** Counter metadata spans every populated campaign, independently of the current filters. */
export function counterOverview(counter: HistoryCounter): {
  range: string | null;
  countingMode: string;
} {
  const campaigns = counter.campaigns.filter(
    (campaign) => campaign.counts.length > 0,
  );
  const years = campaigns.map((campaign) => campaign.year);
  const first = years.length ? Math.min(...years) : undefined;
  const last = years.length ? Math.max(...years) : undefined;
  const modes = new Set(campaigns.map((campaign) => campaign.countingMode));
  const permanent = modes.has('PERMANENT');
  const temporary = modes.has('TEMPORARY');
  let countingMode =
    permanent && temporary
      ? 'Permanent et temporaire selon les campagnes'
      : permanent
        ? 'Permanent'
        : temporary
          ? 'Temporaire'
          : 'Non renseigné';
  if ((permanent || temporary) && modes.has('UNKNOWN'))
    countingMode += ' (non renseigné pour certaines campagnes)';
  return {
    range:
      first === undefined
        ? null
        : first === last
          ? String(first)
          : `${first}–${last}`,
    countingMode,
  };
}
