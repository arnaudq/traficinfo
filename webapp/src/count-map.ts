import L from 'leaflet';
import { countLayers, createCountLoader } from './count-data.ts';
import type { CountLayer, CounterProperties } from './count-data.ts';
import { element } from './dom.ts';
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
): HTMLElement {
  const popup = element('section', null, 'counter-popup');
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
  let revision = 0;
  map.createPane('counts').style.zIndex = '450';

  function clear() {
    revision += 1;
    map.closePopup();
    layers.clearLayers();
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
    const results = await Promise.allSettled(
      countLayers(config.geoJsonBaseUrl, selected).map(async (definition) => {
        const data = await load(definition.url);
        // A completed older request must never restore a map the user has left.
        if (currentRevision !== revision) return;
        const layer = L.geoJSON(data, {
          pane: 'counts',
          style: (feature) => ({
            color: countColor(feature?.properties ?? {}),
            weight: 4,
          }),
          pointToLayer: (feature, position) =>
            L.circleMarker(position, {
              pane: 'counts',
              radius: definition.population === 'BICYCLES' ? 6 : 8,
              color: countColor(feature.properties),
              weight: 2,
              dashArray:
                definition.population === 'BICYCLES' ? '2 2' : undefined,
              fillOpacity: definition.population === 'BICYCLES' ? 0.35 : 0.8,
            }),
          onEachFeature(feature, counter) {
            counter.bindPopup(
              () => counterPopup(feature.properties, definition),
              { maxWidth: 330 },
            );
            const population =
              definition.population === 'BICYCLES' ? 'Vélos' : 'Véhicules';
            const label = `${population} : ${String(feature.properties.name ?? feature.properties.moyenne_jour_annuelle ?? 'Comptage')}`;
            counter.bindTooltip(element('span', label));
            counter.on('add', () => {
              if (!(counter instanceof L.Path)) return;
              const path = counter.getElement();
              if (!path) return;
              path.setAttribute('tabindex', '0');
              path.setAttribute('role', 'button');
              path.setAttribute('aria-label', label);
              path.addEventListener('keydown', (event) => {
                const key = (event as KeyboardEvent).key;
                if (key === 'Enter' || key === ' ') {
                  event.preventDefault();
                  counter.openPopup();
                }
              });
            });
          },
        });
        layers.addLayer(layer);
        count += data.features.length;
      }),
    );
    if (currentRevision !== revision) return;
    const failures = results.flatMap((result, index) => {
      if (result.status !== 'rejected') return [];
      console.error(result.reason);
      const definition = countLayers(config.geoJsonBaseUrl, selected)[index];
      return [
        `${definition.sourceId} (${definition.population === 'BICYCLES' ? 'vélos' : 'véhicules'})`,
      ];
    });
    report(
      `${selected.name} — ${count} comptage(s).${failures.length ? ` Couches indisponibles : ${failures.join(', ')}. Sélectionnez à nouveau la carte pour réessayer.` : ''}`,
    );
  }

  return { select, clear };
}
