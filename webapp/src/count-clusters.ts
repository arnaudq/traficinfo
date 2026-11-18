import L from 'leaflet';
import 'leaflet.markercluster';
import 'leaflet.markercluster/dist/MarkerCluster.css';
import { element } from './dom.ts';
import type { Population } from './types.ts';

/** One group per population combines publishers without mixing vehicles and bicycles. */
export function createCountClusters(
  population: Population,
): L.MarkerClusterGroup {
  const bicycles = population === 'BICYCLES';
  const label = bicycles ? 'vélos' : 'véhicules';
  const group = L.markerClusterGroup({
    maxClusterRadius: 50,
    showCoverageOnHover: false,
    zoomToBoundsOnClick: true,
    spiderfyOnMaxZoom: true,
    clusterPane: 'counts',
    iconCreateFunction(cluster) {
      const count = cluster.getChildCount();
      const size = count < 10 ? 32 : count < 100 ? 40 : 48;
      const content = element('span', String(count));
      content.append(element('span', ` comptages ${label}`, 'visually-hidden'));
      return L.divIcon({
        html: content,
        className: `count-cluster ${bicycles ? 'bicycle-cluster' : 'vehicle-cluster'}`,
        iconSize: [size, size],
      });
    },
  });
  group.on('clustermouseover', (event: L.LeafletEvent) => {
    const cluster = (event as L.LeafletEvent & { layer: L.MarkerCluster })
      .layer;
    cluster
      .bindTooltip(
        element(
          'span',
          `${cluster.getChildCount()} comptages ${label} — cliquer pour explorer`,
        ),
      )
      .openTooltip();
  });
  return group;
}

/** DOM markers retain the count-circle appearance and support clustering/spiderfy. */
export function counterIcon(population: Population, color: string): L.DivIcon {
  const circle = element('span');
  circle.style.borderColor = color;
  circle.style.backgroundColor = color;
  circle.setAttribute('aria-hidden', 'true');
  const bicycles = population === 'BICYCLES';
  return L.divIcon({
    html: circle,
    className: `count-marker${bicycles ? ' bicycle-marker' : ''}`,
    iconSize: bicycles ? [12, 12] : [16, 16],
  });
}
