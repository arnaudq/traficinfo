import 'leaflet/dist/leaflet.css';
import './styles.css';
import { createMap } from './map.js';
import { loadOverview } from './overview.js';

const map = createMap('map');
loadOverview(map).catch((error) => {
  console.error(error);
  document.getElementById('map-status').textContent =
    'Les territoires ne peuvent pas être affichés.';
});
