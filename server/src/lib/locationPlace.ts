export type LocationPlace = { doorplate: string | null; shop_name: string | null; source: 'nominatim' };
const object = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim().length > 0 && value.length <= 500 ? value.trim() : null;

// null means boundary/invalid evidence; it must not establish store membership.
function insideRing(value: unknown, x: number, y: number): boolean | null {
  if (!Array.isArray(value) || value.length < 4) return null;
  const ring: number[][] = [];
  for (const point of value) {
    if (!Array.isArray(point) || point.length < 2 || !Number.isFinite(point[0]) || !Number.isFinite(point[1])) return null;
    ring.push(point);
  }
  if (ring[0]![0] !== ring.at(-1)![0] || ring[0]![1] !== ring.at(-1)![1]) return null;
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]! as [number, number];
    const [xj, yj] = ring[j]! as [number, number];
    if (Math.abs((x - xi) * (yj - yi) - (y - yi) * (xj - xi)) < 1e-12 &&
        x >= Math.min(xi, xj) && x <= Math.max(xi, xj) && y >= Math.min(yi, yj) && y <= Math.max(yi, yj)) return null;
    if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
function insidePolygon(value: unknown, lng: number, lat: number): boolean {
  return Array.isArray(value) && value.length > 0 && insideRing(value[0], lng, lat) === true &&
    value.slice(1).every(ring => insideRing(ring, lng, lat) === false);
}

/** Interpret only returned map evidence, never the nearest POI as a visited store. */
export function parseLocationPlace(value: unknown, lat: number, lng: number): LocationPlace {
  const data = object(value), address = object(data.address), geometry = object(data.geojson);
  const number = text(address.house_number), road = text(address.road);
  const category = text(data.category) ?? text(data.class);
  const isShop = category === 'shop' || (category === 'amenity' &&
    ['restaurant', 'cafe', 'fast_food', 'bar', 'pub', 'food_court', 'ice_cream', 'pharmacy', 'bank', 'fuel'].includes(String(data.type)));
  const covered = Number.isFinite(lat) && Number.isFinite(lng) &&
    (geometry.type === 'Polygon' ? insidePolygon(geometry.coordinates, lng, lat) :
      geometry.type === 'MultiPolygon' && Array.isArray(geometry.coordinates) &&
        geometry.coordinates.some(polygon => insidePolygon(polygon, lng, lat)));
  return { doorplate: number ? `${road ?? ''}${number}` : null,
    shop_name: isShop && covered ? text(data.name) : null, source: 'nominatim' };
}
