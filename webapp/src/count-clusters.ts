import L from 'leaflet';
import carIconUrl from './icons/car.svg';
import bicycleIconUrl from './icons/bicycle.svg';
import 'leaflet.markercluster';
import 'leaflet.markercluster/dist/MarkerCluster.css';
import { element } from './dom.ts';
import type { Population } from './types.ts';
import type { AccidentLayer } from './accident-data.ts';

/** One group per population combines publishers without mixing vehicles and bicycles. */
export function createCountClusters(
  population: Population | AccidentLayer,
): L.MarkerClusterGroup {
  const bicycles = population === 'BICYCLES';
  const accidents =
    population === 'FATAL_ACCIDENTS' || population === 'NON_FATAL_ACCIDENTS';
  const label = accidents
    ? population === 'FATAL_ACCIDENTS'
      ? 'accidents mortels'
      : 'accidents non mortels'
    : `comptages ${bicycles ? 'vélos' : 'véhicules'}`;
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
      content.append(element('span', ` ${label}`, 'visually-hidden'));
      return L.divIcon({
        html: content,
        className: `count-cluster ${accidents ? `accident-cluster${population === 'FATAL_ACCIDENTS' ? ' fatal-accident-cluster' : ''}` : bicycles ? 'bicycle-cluster' : 'vehicle-cluster'}`,
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
          `${cluster.getChildCount()} ${label} — cliquer pour explorer`,
        ),
      )
      .openTooltip();
  });
  return group;
}

/** Original uMap pictograms on traffic-volume coloured markers. */
export function counterIcon(
  population: Population | 'ACCIDENTS',
  color: string,
): L.DivIcon {
  const background = element('span');
  background.style.backgroundColor = color;
  background.setAttribute('aria-hidden', 'true');
  const pictogram = element('img');
  pictogram.src = population === 'BICYCLES' ? bicycleIconUrl : carIconUrl;
  pictogram.alt = '';
  pictogram.draggable = false;
  if (population === 'ACCIDENTS') background.textContent = '!';
  else background.append(pictogram);
  return L.divIcon({
    html: background,
    className:
      population === 'ACCIDENTS'
        ? 'count-marker accident-marker'
        : 'count-marker',
    iconSize: [28, 28],
  });
}
