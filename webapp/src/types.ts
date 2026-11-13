import type { Feature, MultiPolygon, Polygon } from 'geojson';

export type Bounds = [west: number, south: number, east: number, north: number];
export type Population = 'VEHICULES' | 'BICYCLES';

interface BoundaryFields {
  boundaryId: string;
  name: string;
  geoJsonPath: string;
}

export type Boundary = BoundaryFields &
  ({ type: 'region'; parentId?: never } | { type: 'city'; parentId: string });

export interface DataSourceSelection {
  id: string;
  types: Population[];
}

export interface MapDefinition {
  id: string;
  boundaryId: string;
  name: string;
  description: string;
  center: [longitude: number, latitude: number];
  zoom: number;
  dataSources: DataSourceSelection[];
  uri?: string;
  properties?: Record<string, unknown>;
  layerProperties?: Record<string, Record<string, unknown>>;
}

export interface MapConfig {
  $schema?: string;
  geoJsonBaseUrl: string;
  boundaries: Record<string, Boundary>;
  maps: Record<string, MapDefinition>;
}

export type PublicMap = Omit<MapDefinition, 'properties' | 'layerProperties'>;

export interface PublicMapConfig {
  geoJsonBaseUrl: string;
  boundaries: Record<string, Boundary & { bounds: Bounds }>;
  maps: Record<string, PublicMap>;
}

export type BoundaryFeature = Feature<
  Polygon | MultiPolygon,
  { boundaryId: string; [key: string]: unknown }
>;
