import './styles.css';
import './counter-page.css';
import { element } from './dom.ts';
import { applicationRoot, mapUrl } from './map-navigation.ts';
import {
  basisLabels,
  createHistoryLoader,
  counterOverview,
  directionKey,
  directionLabel,
  historySamples,
  historySelection,
  modeLabels,
  populationLabels,
} from './history-data.ts';
import type {
  CounterHistory,
  HistoryCounter,
  HistoryPopulation,
  HistorySample,
  HistorySelection,
} from './history-data.ts';
import type { PublicMapConfig } from './types.ts';
import { historyChart } from './history-chart.ts';

const numbers = new Intl.NumberFormat('fr-FR');
const dates = new Intl.DateTimeFormat('fr-FR', {
  dateStyle: 'medium',
  timeZone: 'UTC',
});
const valueLabel = (value: number | null): string =>
  value === null ? '—' : numbers.format(value);
const dateLabel = (value: string | null): string =>
  value === null ? '—' : dates.format(new Date(value));

function section(title: string): HTMLElement {
  const result = element('section', null, 'history-section');
  result.append(element('h3', title));
  return result;
}
function detail(list: HTMLDListElement, label: string, value: string | null) {
  if (value !== null) list.append(element('dt', label), element('dd', value));
}
function option(select: HTMLSelectElement, value: string, label: string) {
  const choice = element('option', label);
  choice.value = value;
  select.append(choice);
}
function control(
  label: string,
  id: string,
): [HTMLLabelElement, HTMLSelectElement] {
  const wrapper = element('label', label);
  wrapper.htmlFor = id;
  const select = element('select');
  select.id = id;
  wrapper.append(select);
  return [wrapper, select];
}
function period(sample: HistorySample): string {
  const { startDate, endDate } = sample.campaign;
  if (startDate && endDate)
    return `Du ${dateLabel(startDate)} au ${dateLabel(endDate)}`;
  if (startDate) return `À partir du ${dateLabel(startDate)}`;
  if (endDate) return `Jusqu’au ${dateLabel(endDate)}`;
  return 'Dates de mesure non renseignées';
}

function measurements(samples: HistorySample[]): HTMLElement {
  const result = section('Mesures par campagne');
  result.append(
    element(
      'p',
      'Une année peut comporter plusieurs campagnes. Les directions ne sont pas additionnées aux sens cumulés. — indique une mesure non renseignée.',
      'history-note',
    ),
  );
  const wrapper = element('div', null, 'history-table-scroll');
  wrapper.tabIndex = 0;
  wrapper.setAttribute('role', 'region');
  wrapper.setAttribute(
    'aria-label',
    'Tableau des mesures, défilement horizontal possible',
  );
  const table = element('table', null, 'history-table');
  table.append(
    element(
      'caption',
      'Toutes les mesures publiées pour la population, le sens et les années sélectionnés.',
    ),
  );
  const head = element('thead');
  const headings = element('tr');
  for (const label of [
    'Année',
    'Campagne et période',
    'Moyenne / jour',
    'Pointe du matin / h',
    'Pointe du soir / h',
    'Vitesse moyenne (km/h)',
    'V85 (km/h)',
  ]) {
    const cell = element('th', label);
    cell.scope = 'col';
    headings.append(cell);
  }
  head.append(headings);
  const body = element('tbody');
  for (const sample of samples.toReversed()) {
    const { count, campaign } = sample;
    const row = element('tr');
    const year = element('th', String(campaign.year));
    year.scope = 'row';
    const campaignCell = element('td', campaign.label ?? 'Campagne');
    campaignCell.append(
      element('small', period(sample)),
      element('small', modeLabels[campaign.countingMode]),
    );
    const average = element('td', valueLabel(count.dailyAverage));
    average.append(element('small', basisLabels[count.dailyAverageBasis]));
    const morning = element('td', valueLabel(count.morningPeakAverage));
    if (count.morningPeakText)
      morning.append(element('small', count.morningPeakText));
    const evening = element('td', valueLabel(count.eveningPeakAverage));
    if (count.eveningPeakText)
      evening.append(element('small', count.eveningPeakText));
    row.append(
      year,
      campaignCell,
      average,
      morning,
      evening,
      element('td', valueLabel(count.meanSpeed)),
      element('td', valueLabel(count.v85Speed)),
    );
    body.append(row);
  }
  table.append(head, body);
  wrapper.append(table);
  result.append(wrapper);
  return result;
}

function coverage(samples: HistorySample[]): HTMLElement | null {
  const available = samples.filter((sample) => sample.count.coverage !== null);
  if (available.length === 0) return null;
  const result = section('Couverture des observations');
  result.append(
    element(
      'p',
      'Un compteur permanent peut avoir des observations incomplètes. La moyenne sur les jours complets observés ne représente pas nécessairement toute l’année.',
      'history-note',
    ),
  );
  for (const sample of available.toReversed()) {
    const data = sample.count.coverage!;
    const details = element('details', null, 'history-coverage');
    details.append(
      element(
        'summary',
        `${sample.campaign.year}${sample.campaign.label ? ` — ${sample.campaign.label}` : ''}`,
      ),
    );
    const list = element('dl', null, 'history-metadata');
    detail(
      list,
      'Première observation',
      data.firstDate === null ? null : dateLabel(data.firstDate),
    );
    detail(
      list,
      'Dernière observation',
      data.lastDate === null ? null : dateLabel(data.lastDate),
    );
    detail(
      list,
      'Jours observés',
      data.observedDays === null ? null : valueLabel(data.observedDays),
    );
    detail(
      list,
      'Jours complets',
      data.completeDays === null ? null : valueLabel(data.completeDays),
    );
    detail(
      list,
      'Heures utilisables',
      data.observedHours === null ? null : valueLabel(data.observedHours),
    );
    detail(
      list,
      'Heures en conflit exclues',
      data.conflictingHours === null ? null : valueLabel(data.conflictingHours),
    );
    details.append(list);
    result.append(details);
  }
  return result;
}

function renderCounter(
  container: HTMLElement,
  history: CounterHistory,
  counter: HistoryCounter,
  selection: HistorySelection,
) {
  const metadata = element('dl', null, 'history-metadata');
  detail(metadata, 'Source', history.dataSourceId);
  detail(metadata, 'Identifiant', counter.codeCentrale);
  detail(metadata, 'Commune', counter.cityName);
  detail(metadata, 'Route', counter.roadReference);
  const overview = counterOverview(counter);
  detail(metadata, 'Historique disponible', overview.range);
  detail(metadata, 'Type de compteur', overview.countingMode);
  container.append(metadata);
  const filters = element('form', null, 'history-filters');
  filters.setAttribute('aria-label', 'Choisir les mesures à afficher');
  filters.addEventListener('submit', (event) => event.preventDefault());
  const [populationControl, population] = control(
    'Population',
    'history-population',
  );
  const [directionControl, direction] = control(
    'Sens de comptage',
    'history-direction',
  );
  const [yearControl, year] = control('Année', 'history-year');
  const populations = new Set(
    counter.campaigns.flatMap((campaign) =>
      campaign.counts.map((count) => count.population),
    ),
  );
  if (selection.population) populations.add(selection.population);
  for (const key of populations) option(population, key, populationLabels[key]);
  population.value =
    selection.population ?? populations.values().next().value ?? 'VEHICULES';
  population.disabled = populations.size === 0;
  filters.append(populationControl, directionControl, yearControl);
  const resultStatus = element('p', null, 'history-note');
  resultStatus.setAttribute('role', 'status');
  const view = element('div');
  container.append(filters, resultStatus, view);

  let activeView: 'charts' | 'table' = 'charts';

  function views(samples: HistorySample[]): HTMLElement {
    const result = element('div', null, 'history-views');
    const tabs = element('div', null, 'history-tabs');
    tabs.setAttribute('role', 'tablist');
    tabs.setAttribute('aria-label', 'Présentation de l’historique');
    const chartTab = element('button', 'Graphiques');
    const tableTab = element('button', 'Tableau');
    const chartPanel = element('div', null, 'history-tabpanel');
    const tablePanel = element('div', null, 'history-tabpanel');
    const entries = [
      { key: 'charts', tab: chartTab, panel: chartPanel },
      { key: 'table', tab: tableTab, panel: tablePanel },
    ] as const;
    function activate(key: 'charts' | 'table', focus = false) {
      activeView = key;
      for (const entry of entries) {
        const selected = entry.key === key;
        entry.tab.setAttribute('aria-selected', String(selected));
        entry.tab.tabIndex = selected ? 0 : -1;
        entry.panel.hidden = !selected;
        if (selected && focus) entry.tab.focus();
      }
    }
    for (const [index, entry] of entries.entries()) {
      entry.tab.type = 'button';
      entry.tab.id = `history-${entry.key}-tab`;
      entry.tab.setAttribute('role', 'tab');
      entry.tab.setAttribute('aria-controls', `history-${entry.key}-panel`);
      entry.panel.id = `history-${entry.key}-panel`;
      entry.panel.setAttribute('role', 'tabpanel');
      entry.panel.setAttribute('aria-labelledby', entry.tab.id);
      entry.panel.tabIndex = 0;
      entry.tab.addEventListener('click', () => activate(entry.key));
      entry.tab.addEventListener('keydown', (event) => {
        let next: number;
        if (event.key === 'ArrowRight') next = (index + 1) % entries.length;
        else if (event.key === 'ArrowLeft')
          next = (index + entries.length - 1) % entries.length;
        else if (event.key === 'Home') next = 0;
        else if (event.key === 'End') next = entries.length - 1;
        else return;
        event.preventDefault();
        activate(entries[next].key, true);
      });
      tabs.append(entry.tab);
    }
    chartPanel.append(historyChart(samples));
    tablePanel.append(measurements(samples));
    const observations = coverage(samples);
    if (observations) tablePanel.append(observations);
    activate(activeView);
    result.append(tabs, chartPanel, tablePanel);
    return result;
  }

  function update() {
    const selectedYear = year.value === 'all' ? undefined : Number(year.value);
    const samples = historySamples(
      counter,
      population.value as HistoryPopulation,
      direction.value,
      selectedYear,
    );
    view.replaceChildren();
    resultStatus.textContent = `${samples.length} relevé(s) pour cette sélection.`;
    if (samples.length === 0) {
      view.append(
        element(
          'p',
          'Aucune mesure publiée pour cette sélection.',
          'history-empty',
        ),
      );
      return;
    }
    const latest =
      samples.findLast((sample) => sample.count.dailyAverage !== null) ??
      samples[samples.length - 1];
    view.append(
      element(
        'p',
        `Dernière moyenne disponible : ${valueLabel(latest.count.dailyAverage)} passages / jour · ${latest.campaign.year}. ${basisLabels[latest.count.dailyAverageBasis]}.`,
        'history-latest',
      ),
      views(samples),
    );
  }
  function updateYears() {
    const previous = year.value;
    year.replaceChildren();
    option(year, 'all', 'Toutes les années');
    const samples = historySamples(
      counter,
      population.value as HistoryPopulation,
      direction.value,
    );
    for (const value of [
      ...new Set(samples.map((sample) => sample.campaign.year)),
    ].sort((a, b) => b - a))
      option(year, String(value), String(value));
    if ([...year.options].some((choice) => choice.value === previous))
      year.value = previous;
    update();
  }
  function updateDirections() {
    direction.replaceChildren();
    const counts = counter.campaigns
      .flatMap((campaign) => campaign.counts)
      .filter((count) => count.population === population.value);
    const choices = new Map(
      counts.map((count) => [directionKey(count), directionLabel(count)]),
    );
    for (const [key, label] of choices) option(direction, key, label);
    const combined = counts.find(
      (count) => count.directionType === 'BIDIRECTIONAL',
    );
    if (combined) direction.value = directionKey(combined);
    direction.disabled = choices.size === 0;
    updateYears();
  }
  population.addEventListener('change', () => {
    const url = new URL(window.location.href);
    url.searchParams.set('type', population.value);
    window.history.replaceState(null, '', url);
    updateDirections();
  });
  direction.addEventListener('change', updateYears);
  year.addEventListener('change', update);
  updateDirections();

  const provenance = section('Sources des données');
  const sources = element('ul');
  for (const id of new Set(
    counter.campaigns.map((campaign) => campaign.datasetId),
  )) {
    const item = element('li');
    let url: URL | undefined;
    try {
      const candidate = new URL(history.datasets[id].url);
      if (candidate.protocol === 'https:' || candidate.protocol === 'http:')
        url = candidate;
    } catch {
      /* Unusable attribution links remain plain text. */
    }
    if (url) {
      const link = element('a', id);
      link.href = url.href;
      item.append(link);
    } else item.textContent = id;
    sources.append(item);
  }
  provenance.append(
    sources,
    element(
      'p',
      `Historique publié le ${dateLabel(history.publishedAt)}.`,
      'history-note',
    ),
  );
  container.append(provenance);
}

const root = applicationRoot();
const loadHistory = createHistoryLoader();
const title = document.getElementById('counter-title')!;
const status = document.getElementById('history-status')!;
const retry = document.getElementById('history-retry')!;
const content = document.getElementById('history-content')!;
const back = document.getElementById('back-to-map') as HTMLAnchorElement;

async function loadPage() {
  status.setAttribute('role', 'status');
  status.textContent = 'Chargement de l’historique…';
  retry.hidden = true;
  content.replaceChildren();
  let selection: HistorySelection;
  try {
    selection = historySelection(new URL(window.location.href));
  } catch (error) {
    status.setAttribute('role', 'alert');
    status.textContent =
      error instanceof Error ? error.message : 'Lien de compteur incorrect.';
    return;
  }
  title.textContent = `Compteur ${selection.id}`;
  try {
    const response = await fetch(new URL('mapconfig.json', root));
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const config = (await response.json()) as PublicMapConfig;
    if (typeof config.geoJsonBaseUrl !== 'string')
      throw new Error('Invalid history base URL');
    const sourceMap =
      selection.mapId && Object.hasOwn(config.maps, selection.mapId)
        ? config.maps[selection.mapId]
        : undefined;
    back.href = mapUrl(root, sourceMap).href;
    const history = await loadHistory(config.geoJsonBaseUrl, selection.source);
    const counter = Object.hasOwn(history.counters, selection.id)
      ? history.counters[selection.id]
      : undefined;
    if (!counter) {
      status.setAttribute('role', 'alert');
      status.textContent = `Le compteur ${selection.id} n’est pas présent dans l’historique publié par ${selection.source}.`;
      return;
    }
    title.textContent =
      counter.displayName ?? `Compteur ${counter.codeCentrale}`;
    document.title = `${title.textContent} — TraficInfo`;
    renderCounter(content, history, counter, selection);
    status.textContent = 'Historique complet des campagnes publiées.';
  } catch (error) {
    console.error(error);
    content.replaceChildren();
    status.setAttribute('role', 'alert');
    status.textContent =
      'L’historique de ce compteur ne peut pas être chargé pour le moment.';
    retry.hidden = false;
  }
}
retry.addEventListener('click', () => {
  void loadPage();
});
void loadPage();
