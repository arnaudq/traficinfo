import L from 'leaflet';
import { boundaryBounds, boundaryUrl, mapsForBoundary } from './territories.js';

const styles = {
  region: { color: '#64748b', weight: 1.5, dashArray: '6 4', fill: false },
  city: {
    color: '#225f4c',
    weight: 2.5,
    fillColor: '#28775d',
    fillOpacity: 0.16,
  },
};

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function mapContent(map) {
  const item = element('li', null, 'map-entry');
  item.append(element('h3', map.name), element('p', map.description));
  const url = map.uri ? new URL(map.uri) : null;
  if (url && ['http:', 'https:'].includes(url.protocol)) {
    const link = element('a', 'Ouvrir la carte uMap');
    link.href = url.href;
    item.append(link);
  }
  return item;
}

function popupContent(config, boundary) {
  const container = element('section', null, 'territory-popup');
  container.append(element('h2', boundary.name));
  const list = element('ul', null, 'map-list');
  for (const map of mapsForBoundary(config, boundary.boundaryId))
    list.append(mapContent(map));
  container.append(list);
  return container;
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${url}`);
  return response.json();
}

/** Load only the configured overview contours; count data has a separate lifecycle. */
export async function loadOverview(map) {
  const status = document.getElementById('map-status');
  const availableMaps = document.getElementById('available-maps');
  let config;
  try {
    config = await fetchJson(new URL('./mapconfig.json', window.location.href));
  } catch (error) {
    console.error(error);
    status.textContent =
      'Les territoires ne peuvent pas être chargés. Réessayez en actualisant la page.';
    return;
  }

  for (const type of ['region', 'city']) {
    map.createPane(type).style.zIndex = type === 'city' ? 420 : 410;
  }
  map.attributionControl.addAttribution(
    'Contours © IGN / <a href="https://www.data.gouv.fr/datasets/contours-administratifs">data.gouv.fr</a>' +
      ' / <a href="https://opendatacommons.org/licenses/odbl/1-0/">ODbL</a>',
  );

  // Also provides keyboard access and map links when an individual contour fails.
  const list = element('ul', null, 'map-list');
  for (const entry of Object.values(config.maps))
    list.append(mapContent(entry));
  availableMaps.replaceChildren(list);

  const results = await Promise.allSettled(
    Object.values(config.boundaries).map(async (boundary) => {
      const feature = await fetchJson(
        boundaryUrl(config.geoJsonBaseUrl, boundary.geoJsonPath),
      );
      boundaryBounds(feature, boundary.boundaryId);
      const layer = L.geoJSON(feature, {
        pane: boundary.type,
        style: styles[boundary.type],
      });
      layer.addTo(map);
      layer.eachLayer((polygon) => {
        polygon.bindPopup(() => popupContent(config, boundary), {
          maxWidth: 350,
        });
        const path = polygon.getElement();
        // Regions are selectable through their outline, even inside child Cities.
        if (boundary.type === 'region') path.style.pointerEvents = 'stroke';
        path.setAttribute('tabindex', '0');
        path.setAttribute('role', 'button');
        path.setAttribute('aria-label', `Cartes : ${boundary.name}`);
        path.addEventListener('keydown', (event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            polygon.openPopup(polygon.getBounds().getCenter());
          }
        });
      });
    }),
  );
  const failures = results.filter((result) => result.status === 'rejected');
  for (const failure of failures) console.error(failure.reason);
  status.textContent = failures.length
    ? `${failures.length} contour(s) indisponible(s). Les cartes restent accessibles dans la liste.`
    : 'Cliquez sur une ville ou sur le contour d’une région pour découvrir les cartes.';
}
