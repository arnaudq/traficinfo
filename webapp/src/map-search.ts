import L from 'leaflet';
import { element } from './dom.ts';
import { searchProviders } from './search-providers.ts';
import type { SearchProvider } from './search-providers.ts';
import type { PublicMap } from './types.ts';

/** Provider-independent search UI and map navigation. */
export function createMapSearch(map: L.Map) {
  const container = element('section', null, 'map-search');
  container.hidden = true;
  container.setAttribute('aria-label', 'Recherche d’adresse ou de lieu');
  const form = element('form');
  form.setAttribute('role', 'search');
  const label = element('label', 'Adresse ou lieu', 'visually-hidden');
  label.htmlFor = 'place-search';
  const input = element('input');
  input.id = 'place-search';
  input.type = 'search';
  input.placeholder = 'Rechercher une adresse ou un lieu';
  input.required = true;
  input.minLength = 3;
  input.maxLength = 200;
  const submit = element('button', 'Rechercher');
  submit.type = 'submit';
  form.append(label, input, submit);
  const status = element('p', null, 'search-status');
  status.setAttribute('role', 'status');
  const results = element('ul', null, 'search-results');
  results.setAttribute('aria-label', 'Résultats de recherche');
  const attribution = element('a', null, 'search-attribution');
  container.append(form, status, results, attribution);
  document.querySelector('.site-header')!.append(container);
  let provider: SearchProvider = searchProviders.ign;
  let pending: AbortController | undefined;
  let marker: L.CircleMarker | undefined;

  function cancel() {
    pending?.abort();
    pending = undefined;
    status.textContent = '';
    results.replaceChildren();
  }
  function reset() {
    cancel();
    marker?.remove();
    marker = undefined;
    input.value = '';
  }
  input.addEventListener('input', cancel);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    cancel();
    const query = input.value.trim();
    if (query.length < 3) {
      status.textContent = 'Saisissez au moins 3 caractères.';
      return;
    }
    const request = new AbortController();
    pending = request;
    status.textContent = 'Recherche en cours…';
    const center = map.getCenter();
    void provider
      .search(query, [center.lng, center.lat], request.signal)
      .then((places) => {
        if (pending !== request) return;
        status.textContent = places.length
          ? `${places.length} résultat(s). Sélectionnez un lieu pour le voir sur la carte.`
          : 'Aucun résultat. Précisez l’adresse ou la ville.';
        for (const place of places) {
          const item = element('li');
          const button = element('button', place.label);
          button.type = 'button';
          button.addEventListener('click', () => {
            const [longitude, latitude] = place.coordinates;
            const position: L.LatLngTuple = [latitude, longitude];
            marker?.remove();
            marker = L.circleMarker(position, {
              radius: 9,
              color: '#173c35',
              fillColor: '#d3911d',
              fillOpacity: 1,
              weight: 3,
            })
              .addTo(map)
              .bindPopup(element('span', place.label));
            map.setView(position, Math.max(map.getZoom(), 16));
            marker.openPopup();
            results.replaceChildren();
            status.textContent = place.label;
            map.getContainer().focus();
          });
          item.append(button);
          results.append(item);
        }
      })
      .catch((error: unknown) => {
        if (pending !== request) return;
        console.error(error);
        status.textContent = 'La recherche est indisponible. Réessayez.';
      });
  });
  return {
    select(selected: PublicMap) {
      reset();
      provider = searchProviders[selected.searchProvider ?? 'ign'];
      attribution.textContent = provider.attribution;
      attribution.href = provider.attributionUrl;
      container.hidden = false;
    },
    hide() {
      reset();
      container.hidden = true;
    },
  };
}
