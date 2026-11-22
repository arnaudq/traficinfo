import { element } from './dom.ts';
import type { HistorySample } from './history-data.ts';

const numberLabel = new Intl.NumberFormat('fr-FR');
export interface ChartPoint {
  x: number;
  y: number;
  sample: HistorySample;
  value: number;
}
export interface ChartBar extends ChartPoint {
  width: number;
  height: number;
}
export interface ChartLayout {
  width: number;
  height: number;
  left: number;
  right: number;
  top: number;
  bottom: number;
  countMaximum: number;
  speedMaximum: number;
  years: { year: number; x: number }[];
  bars: ChartBar[];
  missingCounts: { x: number; sample: HistorySample }[];
  meanSpeed: ChartPoint[][];
  v85Speed: ChartPoint[][];
}

function scaleMaximum(values: (number | null)[]): number {
  const maximum = values.reduce<number>(
    (max, value) => Math.max(max, value ?? 0),
    0,
  );
  if (maximum === 0) return 4;
  const perTick = maximum / 4;
  const order = 10 ** Math.floor(Math.log10(perTick));
  const multiplier = [1, 2, 5, 10].find((value) => value * order >= perTick)!;
  return Math.max(1, multiplier * order) * 4;
}

/** A chronological year axis, independent count/speed scales, and separate marks for each campaign. */
export function historyChartLayout(samples: HistorySample[]): ChartLayout {
  const width = 900,
    height = 420,
    left = 76,
    right = 822,
    top = 46,
    bottom = 346;
  const sorted = [...samples].sort(
    (a, b) =>
      a.campaign.year - b.campaign.year ||
      (a.campaign.startDate ?? '').localeCompare(b.campaign.startDate ?? '') ||
      a.campaign.id.localeCompare(b.campaign.id) ||
      a.count.id.localeCompare(b.count.id),
  );
  const first = sorted[0]?.campaign.year ?? 0;
  const last = sorted.at(-1)?.campaign.year ?? first;
  const slot = (right - left) / (last - first + 1);
  const countMaximum = scaleMaximum(
    sorted.map((sample) => sample.count.dailyAverage),
  );
  const speedMaximum = scaleMaximum(
    sorted.flatMap((sample) => [sample.count.meanSpeed, sample.count.v85Speed]),
  );
  const perYear = new Map<number, number>();
  for (const sample of sorted)
    perYear.set(
      sample.campaign.year,
      (perYear.get(sample.campaign.year) ?? 0) + 1,
    );
  const seen = new Map<number, number>();
  const positions = sorted.map((sample) => {
    const year = sample.campaign.year;
    const groupSize = perYear.get(year)!;
    const index = seen.get(year) ?? 0;
    seen.set(year, index + 1);
    const groupWidth = slot * 0.7;
    return {
      sample,
      x:
        left +
        slot * (year - first + 0.5) +
        ((index + 0.5) / groupSize - 0.5) * groupWidth,
      width: Math.min(36, (groupWidth / groupSize) * 0.8),
    };
  });
  const bars: ChartBar[] = [];
  const missingCounts: ChartLayout['missingCounts'] = [];
  for (const position of positions) {
    const value = position.sample.count.dailyAverage;
    if (value === null) {
      missingCounts.push(position);
      continue;
    }
    const height = ((bottom - top) * value) / countMaximum;
    bars.push({ ...position, y: bottom - height, height, value });
  }
  function segments(key: 'meanSpeed' | 'v85Speed'): ChartPoint[][] {
    const result: ChartPoint[][] = [];
    let current: ChartPoint[] = [];
    let previousYear: number | undefined;
    for (const { sample, x } of positions) {
      const value = sample.count[key];
      if (
        value === null ||
        (previousYear !== undefined && sample.campaign.year > previousYear + 1)
      ) {
        if (current.length) result.push(current);
        current = [];
      }
      if (value !== null)
        current.push({
          sample,
          x,
          value,
          y: bottom - ((bottom - top) * value) / speedMaximum,
        });
      previousYear = sample.campaign.year;
    }
    if (current.length) result.push(current);
    return result;
  }
  const years: ChartLayout['years'] = [];
  if (sorted.length) {
    const step = Math.max(1, Math.ceil((last - first + 1) / 16));
    for (let year = first; year <= last; year += step)
      years.push({ year, x: left + slot * (year - first + 0.5) });
    if (years.at(-1)?.year !== last)
      years.push({ year: last, x: left + slot * (last - first + 0.5) });
  }
  return {
    width,
    height,
    left,
    right,
    top,
    bottom,
    countMaximum,
    speedMaximum,
    years,
    bars,
    missingCounts,
    meanSpeed: segments('meanSpeed'),
    v85Speed: segments('v85Speed'),
  };
}

function svg<K extends keyof SVGElementTagNameMap>(
  tag: K,
  attributes: Record<string, string | number>,
  text?: string,
): SVGElementTagNameMap[K] {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attributes))
    node.setAttribute(key, String(value));
  if (text !== undefined) node.textContent = text;
  return node;
}
function sampleLabel(sample: HistorySample): string {
  const { year, label, startDate, endDate } = sample.campaign;
  return `${year}${label ? ` · ${label}` : ''}${startDate ? ` · ${startDate}` : ''}${endDate ? ` – ${endDate}` : ''}`;
}
function describe(mark: SVGElement, text: string) {
  mark.setAttribute('tabindex', '0');
  mark.setAttribute('aria-label', text);
  mark.append(svg('title', {}, text));
}

/** Count bars use the left axis; measured speed lines use the right axis in km/h. */
export function historyChart(samples: HistorySample[]): HTMLElement {
  const layout = historyChartLayout(samples);
  const figure = element('figure', null, 'history-chart');
  figure.append(element('figcaption', 'Comptages et vitesses par année'));
  const legend = element('ul', null, 'history-chart-legend');
  const series: [string, string][] = [
    ['count', 'Moyenne quotidienne · passages/jour'],
  ];
  if (layout.meanSpeed.length) series.push(['mean', 'Vitesse moyenne · km/h']);
  if (layout.v85Speed.length) series.push(['v85', 'V85 · km/h']);
  for (const [key, label] of series) {
    const item = element('li');
    const symbol = element('i', null, `history-legend-${key}`);
    symbol.setAttribute('aria-hidden', 'true');
    item.append(symbol, document.createTextNode(label));
    legend.append(item);
  }
  figure.append(legend);
  if (!layout.meanSpeed.length && !layout.v85Speed.length) {
    figure.append(
      element(
        'p',
        'Aucune vitesse publiée pour cette sélection.',
        'history-note',
      ),
    );
  }
  const scroll = element('div', null, 'history-chart-scroll');
  scroll.tabIndex = 0;
  scroll.setAttribute('role', 'region');
  scroll.setAttribute(
    'aria-label',
    'Graphique annuel, défilement horizontal possible',
  );
  const canvas = svg('svg', {
    viewBox: `0 0 ${layout.width} ${layout.height}`,
    role: 'img',
    'aria-labelledby': 'history-chart-title history-chart-description',
  });
  canvas.append(
    svg(
      'title',
      { id: 'history-chart-title' },
      'Comptages en barres et vitesses en courbes, par année',
    ),
    svg(
      'desc',
      { id: 'history-chart-description' },
      'Années sur l’axe horizontal. Moyenne quotidienne à gauche, vitesses en km/h à droite. Une barre par campagne ; aucune mesure manquante n’est estimée. Les valeurs exactes sont dans l’onglet Tableau.',
    ),
  );
  const { left, right, top, bottom } = layout;
  for (let tick = 0; tick <= 4; tick++) {
    const y = bottom - ((bottom - top) * tick) / 4;
    canvas.append(
      svg('line', { x1: left, y1: y, x2: right, y2: y, class: 'history-grid' }),
      svg(
        'text',
        {
          x: left - 12,
          y: y + 4,
          'text-anchor': 'end',
          class: 'history-axis-label',
        },
        numberLabel.format(Math.round((layout.countMaximum * tick) / 4)),
      ),
    );
    if (layout.meanSpeed.length || layout.v85Speed.length)
      canvas.append(
        svg(
          'text',
          {
            x: right + 12,
            y: y + 4,
            class: 'history-axis-label',
          },
          numberLabel.format(Math.round((layout.speedMaximum * tick) / 4)),
        ),
      );
  }
  canvas.append(
    svg(
      'text',
      { x: left, y: 22, class: 'history-axis-title' },
      'Passages / jour',
    ),
  );
  if (layout.meanSpeed.length || layout.v85Speed.length)
    canvas.append(
      svg(
        'text',
        {
          x: right,
          y: 22,
          'text-anchor': 'end',
          class: 'history-axis-title',
        },
        'Vitesses · km/h',
      ),
    );
  for (const { year, x } of layout.years)
    canvas.append(
      svg('line', {
        x1: x,
        y1: bottom,
        x2: x,
        y2: bottom + 5,
        class: 'history-axis',
      }),
      svg(
        'text',
        {
          x,
          y: bottom + 25,
          'text-anchor': 'middle',
          class: 'history-axis-label',
        },
        String(year),
      ),
    );
  canvas.append(
    svg(
      'text',
      {
        x: (left + right) / 2,
        y: layout.height - 20,
        'text-anchor': 'middle',
        class: 'history-axis-title',
      },
      'Année',
    ),
  );
  for (const bar of layout.bars) {
    const mark = svg('rect', {
      x: bar.x - bar.width / 2,
      y: bar.value === 0 ? bottom - 2 : bar.y,
      width: bar.width,
      height: Math.max(2, bar.height),
      rx: 2,
      class: 'history-count-bar',
    });
    describe(
      mark,
      `${sampleLabel(bar.sample)} : ${numberLabel.format(bar.value)} passages/jour`,
    );
    canvas.append(mark);
  }
  for (const { x, sample } of layout.missingCounts) {
    const mark = svg(
      'text',
      { x, y: bottom - 7, 'text-anchor': 'middle', class: 'history-missing' },
      '—',
    );
    describe(
      mark,
      `${sampleLabel(sample)} : moyenne quotidienne non renseignée`,
    );
    canvas.append(mark);
  }
  for (const [key, segments, label] of [
    ['mean', layout.meanSpeed, 'Vitesse moyenne'],
    ['v85', layout.v85Speed, 'V85'],
  ] as const) {
    for (const segment of segments) {
      if (segment.length > 1)
        canvas.append(
          svg('polyline', {
            points: segment.map((point) => `${point.x},${point.y}`).join(' '),
            class: `history-speed-line history-speed-${key}`,
          }),
        );
      for (const point of segment) {
        const mark = svg('circle', {
          cx: point.x,
          cy: point.y,
          r: 4,
          class: `history-speed-point history-speed-${key}`,
        });
        describe(
          mark,
          `${sampleLabel(point.sample)} : ${label} ${numberLabel.format(point.value)} km/h`,
        );
        canvas.append(mark);
      }
    }
  }
  scroll.append(canvas);
  figure.append(
    scroll,
    element(
      'p',
      'Une barre par campagne ; plusieurs campagnes d’une même année restent distinctes. Les années ou mesures manquantes ne sont pas estimées et interrompent les courbes. Les deux axes utilisent des unités différentes.',
      'history-note',
    ),
  );
  return figure;
}
