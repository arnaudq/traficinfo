import {
  accidentColor,
  accidentLayer,
  accidentTooltip,
  accidentTotals,
} from './accident-data.ts';
import L from 'leaflet';
import { createCountClusters, counterIcon } from './count-clusters.ts';
import { countLayers, createCountLoader } from './count-data.ts';
import type { CountLayer, CounterProperties } from './count-data.ts';
import { element } from './dom.ts';
import { counterDetailsUrl } from './history-data.ts';
import type { PublicMap, PublicMapConfig } from './types.ts';

function countColor(properties: CounterProperties): string {
  const average = properties.moyenne_jour_annuelle;
  if (typeof average !== 'number' || !Number.isFinite(average))
    return '#64748b';
  return average <= 1000 ? '#15803d' : average <= 5000 ? '#2563eb' : '#dc2626';
}

function counterPopup(
  properties: CounterProperties,
  layer: CountLayer,
  mapId: string,
): HTMLElement {
  const popup = element('section', null, 'counter-popup');
  if (layer.population === 'ACCIDENTS') {
    popup.append(
      element('h2', `Accident ${String(properties.accidentId ?? '')}`),
    );
    const details = element('dl');
    const totals = accidentTotals(properties);
    const fields: [string, unknown][] = [
      ['Source', layer.sourceId],
      ['Date', properties.date],
      ['Heure', properties.time],
      ['Année', properties.year],
      ['Voie OSM', properties.osmWayId],
      ['Personnes tuées', totals.KILLED],
      ['Blessés hospitalisés', totals.HOSPITALIZED],
      ['Blessés légers', totals.LIGHTLY_INJURED],
      ['Indemnes', totals.UNINJURED],
      ['Gravité inconnue', totals.UNKNOWN],
    ];
    for (const [label, value] of fields) {
      if (value !== undefined && value !== null)
        details.append(element('dt', label), element('dd', String(value)));
    }
    popup.append(details);
    return popup;
  }

  const title =
    properties.description ?? properties.code_centrale ?? layer.sourceId;
  popup.append(element('h2', String(title)));
  const fields: [string, unknown][] = [
    ['Source', layer.sourceId],
    ['Compteur', properties.code_centrale],
    ['Population', layer.population === 'BICYCLES' ? 'Vélos' : 'Véhicules'],
    ['Année', properties.annee],
    ['Moyenne par jour', properties.moyenne_jour_annuelle],
    ['Historique annuel', properties.moyenne_annuelle_history],
  ];
  if (layer.population === 'VEHICULES') {
    for (const [label, key] of [
      ['Vitesse moyenne', 'mean_speed'],
      ['V85', 'v85_speed'],
      ['Limitation de vitesse', 'maximum_speed'],
    ]) {
      const value = properties[key];
      if (value !== undefined && value !== null && value !== '')
        fields.push([label, `${String(value)} km/h`]);
    }
  }
  const details = element('dl');
  for (const [label, value] of fields) {
    if (value === undefined || value === null || value === '') continue;
    details.append(element('dt', label), element('dd', String(value)));
  }
  popup.append(details);
  if (
    typeof properties.code_centrale === 'string' &&
    properties.code_centrale.trim()
  ) {
    const link = element('a', 'Plus de détails', 'counter-details-link');
    link.href = counterDetailsUrl(new URL('./', document.baseURI), {
      source: layer.sourceId,
      id: properties.code_centrale,
      population: layer.population,
      mapId,
    }).href;
    popup.append(link);
  }
  return popup;
}

/** Own Leaflet count layers independently of the overview and surrounding UI. */
export function createCountMap(
  map: L.Map,
  config: PublicMapConfig,
  report: (message: string) => void,
) {
  const load = createCountLoader();
  const layers = L.layerGroup().addTo(map);
  const clusters = {
    VEHICULES: createCountClusters('VEHICULES').addTo(map),
    BICYCLES: createCountClusters('BICYCLES').addTo(map),
    FATAL_ACCIDENTS: createCountClusters('FATAL_ACCIDENTS').addTo(map),
    NON_FATAL_ACCIDENTS: createCountClusters('NON_FATAL_ACCIDENTS'),
  };
  L.control
    .layers(
      {},
      {
        'Comptages véhicules': clusters.VEHICULES,
        'Comptages vélos': clusters.BICYCLES,
        'Accidents mortels': clusters.FATAL_ACCIDENTS,
        'Accidents non mortels': clusters.NON_FATAL_ACCIDENTS,
      },
    )
    .addTo(map);
  let revision = 0;
  map.createPane('counts').style.zIndex = '450';

  function clear() {
    revision += 1;
    map.closePopup();
    layers.clearLayers();
    for (const group of Object.values(clusters)) group.clearLayers();
  }

  async function select(selected: PublicMap) {
    clear();
    const currentRevision = revision;
    const [west, south, east, north] =
      config.boundaries[selected.boundaryId].bounds;
    map.fitBounds(
      [
        [south, west],
        [north, east],
      ],
      { padding: [24, 24] },
    );
    report(`Chargement : ${selected.name}…`);
    let count = 0;
    let accidents = 0;
    const results = await Promise.allSettled(
      countLayers(config.geoJsonBaseUrl, selected).map(async (definition) => {
        const data = await load(definition.url);
        // A completed older request must never restore a map the user has left.
        if (currentRevision !== revision) return;
        const layer = L.geoJSON(data, {
          pane: 'counts',
          style: (feature) => ({
            color:
              definition.population === 'ACCIDENTS'
                ? accidentColor(feature?.properties ?? {})
                : countColor(feature?.properties ?? {}),
            weight: 4,
          }),
          pointToLayer: (feature, position) =>
            L.marker(position, {
              pane: 'counts',
              icon: counterIcon(
                definition.population,
                definition.population === 'ACCIDENTS'
                  ? accidentColor(feature.properties)
                  : countColor(feature.properties),
              ),
              keyboard: true,
            }),
          onEachFeature(feature, counter) {
            counter.bindPopup(
              () => counterPopup(feature.properties, definition, selected.id),
              { maxWidth: 330 },
            );
            const population =
              definition.population === 'ACCIDENTS'
                ? 'Accident'
                : definition.population === 'BICYCLES'
                  ? 'Vélos'
                  : 'Véhicules';
            const label =
              definition.population === 'ACCIDENTS'
                ? accidentTooltip(feature.properties)
                : `${population} : ${String(feature.properties.name ?? feature.properties.moyenne_jour_annuelle ?? 'Comptage')}`;
            counter.bindTooltip(element('span', label));
            counter.on('add', () => {
              if (!(counter instanceof L.Path || counter instanceof L.Marker))
                return;
              const path = counter.getElement();
              if (!(path instanceof HTMLElement || path instanceof SVGElement))
                return;
              path.setAttribute('tabindex', '0');
              path.setAttribute('role', 'button');
              path.setAttribute('aria-label', label);
              path.onkeydown = (event) => {
                const key = (event as KeyboardEvent).key;
                if (
                  key === ' ' ||
                  (key === 'Enter' && counter instanceof L.Path)
                ) {
                  event.preventDefault();
                  counter.openPopup();
                }
              };
            });
          },
        });
        // Only point markers are clustered; lines and polygons keep their geometry.
        // The loader retains the original data independently for future filtering.
        const points: L.Marker[] = [];
        const fatalPoints: L.Marker[] = [];
        const nonFatalPoints: L.Marker[] = [];
        function collect(counter: L.Layer) {
          if (counter instanceof L.Marker) {
            if (definition.population === 'ACCIDENTS') {
              const target =
                accidentLayer(counter.feature?.properties ?? {}) ===
                'FATAL_ACCIDENTS'
                  ? fatalPoints
                  : nonFatalPoints;
              target.push(counter);
            } else points.push(counter);
          } else if (counter instanceof L.LayerGroup)
            counter.eachLayer(collect);
          else layers.addLayer(counter);
        }
        layer.eachLayer(collect);
        if (definition.population === 'ACCIDENTS') {
          clusters.FATAL_ACCIDENTS.addLayers(fatalPoints);
          clusters.NON_FATAL_ACCIDENTS.addLayers(nonFatalPoints);
        } else clusters[definition.population].addLayers(points);
        if (definition.population === 'ACCIDENTS')
          accidents += data.features.length;
        else count += data.features.length;
      }),
    );
    if (currentRevision !== revision) return;
    const failures = results.flatMap((result, index) => {
      if (result.status !== 'rejected') return [];
      console.error(result.reason);
      const definition = countLayers(config.geoJsonBaseUrl, selected)[index];
      return [
        `${definition.sourceId} (${definition.population === 'ACCIDENTS' ? 'accidents' : definition.population === 'BICYCLES' ? 'vélos' : 'véhicules'})`,
      ];
    });
    report(
      `${selected.name} — ${count} comptage(s), ${accidents} accident(s).${failures.length ? ` Couches indisponibles : ${failures.join(', ')}. Sélectionnez à nouveau la carte pour réessayer.` : ''}`,
    );
  }

  return { select, clear };
}
