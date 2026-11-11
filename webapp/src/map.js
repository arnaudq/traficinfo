import L from 'leaflet';

/** Create the base map inside the given HTML element. */
export function createMap(elementId) {
  // Leaflet coordinates are ordered as latitude, longitude.
  const map = L.map(elementId).setView([46.6, 2.4], 6);

  const baseLayer = L.tileLayer(
    'https://{s}.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png',
    {
      minZoom: 1,
      maxZoom: 20,
      attribution:
        'Données © <a href="https://www.openstreetmap.org/copyright">les contributeurs OpenStreetMap</a> / ODbL' +
        ' · Fond <a href="https://www.openstreetmap.fr/">OpenStreetMap France</a>' +
        ' / <a href="https://creativecommons.org/licenses/by-sa/2.0/">CC BY-SA</a>',
    },
  );

  baseLayer.addTo(map);
  return map;
}
