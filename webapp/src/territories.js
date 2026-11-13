/** Resolve public Object Storage paths without replacing the final base segment. */
export function boundaryUrl(base, path) {
  return `${base.replace(/\/$/, '')}/${path}`;
}

/** Maps attached to this territory, including child City maps for a Region. */
export function mapsForBoundary(config, boundaryId) {
  const ids = new Set([boundaryId]);
  if (config.boundaries[boundaryId].type === 'region') {
    for (const boundary of Object.values(config.boundaries)) {
      if (boundary.parentId === boundaryId) ids.add(boundary.boundaryId);
    }
  }
  return Object.values(config.maps).filter((map) => ids.has(map.boundaryId));
}

/** Validate published polygon geometry and derive its WGS84 extent. */
export function boundaryBounds(feature, boundaryId) {
  if (
    feature?.type !== 'Feature' ||
    feature.properties?.boundaryId !== boundaryId
  ) {
    throw new Error(`Identifiant de contour incorrect : ${boundaryId}`);
  }
  const geometry = feature.geometry;
  const polygons =
    geometry?.type === 'Polygon'
      ? [geometry.coordinates]
      : geometry?.type === 'MultiPolygon'
        ? geometry.coordinates
        : null;
  const bounds = [Infinity, Infinity, -Infinity, -Infinity];
  const invalid = () => {
    throw new Error(`Géométrie de contour incorrecte : ${boundaryId}`);
  };
  if (!Array.isArray(polygons) || polygons.length === 0) invalid();
  for (const polygon of polygons) {
    if (!Array.isArray(polygon) || polygon.length === 0) invalid();
    for (const ring of polygon) {
      if (!Array.isArray(ring) || ring.length < 4) invalid();
      for (const point of ring) {
        if (
          !Array.isArray(point) ||
          point.length !== 2 ||
          !point.every(Number.isFinite) ||
          Math.abs(point[0]) > 180 ||
          Math.abs(point[1]) > 90
        )
          invalid();
        bounds[0] = Math.min(bounds[0], point[0]);
        bounds[1] = Math.min(bounds[1], point[1]);
        bounds[2] = Math.max(bounds[2], point[0]);
        bounds[3] = Math.max(bounds[3], point[1]);
      }
      if (ring[0].some((value, index) => value !== ring.at(-1)[index]))
        invalid();
    }
  }
  return bounds;
}
