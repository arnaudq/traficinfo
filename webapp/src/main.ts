import 'leaflet/dist/leaflet.css';
import './styles.css';
import { createMap } from './map.ts';
import { loadOverview } from './overview.ts';

const map = createMap('map');
loadOverview(map).catch((error) => {
  console.error(error);
  const status = document.getElementById('map-status');
  if (status)
    status.textContent = 'Les territoires ne peuvent pas être affichés.';
});
