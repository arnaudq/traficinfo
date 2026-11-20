import L from 'leaflet';
import { basemap } from './config.ts';

/** Create the base map inside the given HTML element. */
export function createMap(elementId: string): L.Map {
  // Leaflet coordinates are ordered as latitude, longitude.
  const map = L.map(elementId).setView([46.6, 2.4], 6);

  const baseLayer = L.tileLayer(basemap.tileUrl, {
    minZoom: basemap.minZoom,
    maxZoom: basemap.maxZoom,
    attribution: basemap.attribution,
  });

  baseLayer.addTo(map);
  // Search results and legends change the space available below the header.
  const resize = new ResizeObserver(() => map.invalidateSize({ pan: false }));
  resize.observe(map.getContainer());
  map.on('unload', () => resize.disconnect());
  return map;
}
