import type { LocationPlace } from './locationPlace.js';
const record = (v: unknown): Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : {};
const text = (v: unknown): string | null => typeof v === 'string' && v.trim() && v.length <= 500 ? v.trim() : null;
const list = (v: unknown): Record<string, unknown>[] => Array.isArray(v) ? v.map(record) : [];
const number = (v: unknown): number | null => (typeof v === 'number' || (typeof v === 'string' && v.trim() !== '')) && Number.isFinite(Number(v)) ? Number(v) : null;
const commercial = (v: unknown) => /^(餐饮服务|购物服务|生活服务|体育休闲服务|住宿服务|金融保险服务|汽车服务|汽车销售|汽车维修|摩托车服务|公司企业)(;|$)/.test(text(v) ?? '');

/** POIs are nearby candidates. Only explicit containment is used for shop names. */
export function parseAmapPlace(value: unknown): LocationPlace {
  const data = record(value), component = record(data.addressComponent), street = record(component.streetNumber);
  const pois = list(data.pois);
  const inside = list(data.aois).filter(a => text(a.name) && number(a.distance) === 0)
    .sort((a, b) => ((number(a.area) ?? 0) > 0 ? number(a.area)! : Infinity) - ((number(b.area) ?? 0) > 0 ? number(b.area)! : Infinity));
  const aoi = inside[0];
  const associated = aoi && text(aoi.id) ? pois.find(p => text(p.id) === text(aoi.id)) : undefined;
  const streetDoorplate = text(street.number) ? `${text(street.street) ?? ''}${text(street.number)}` : null;
  // The nearest street-number point can be on the other side of a road. The
  // containing object's own address is preferable when the API links the IDs.
  const associatedAddress = text(associated?.address);
  const doorplate = associatedAddress && /[0-9０-９一二三四五六七八九十百]+.*号/.test(associatedAddress) ? associatedAddress : streetDoorplate;
  const shop = associated && commercial(associated.type) ? text(associated.name) : null;
  return { doorplate, shop_name: shop, source: 'amap',
    building_name: text(record(component.building).name) ?? (aoi ? text(aoi.name) : null),
    ...(shop ? { shop_match: 'contained_aoi' } : {}) };
}

let nextRequestAt = 0;
class AmapError extends Error {}
async function request(path: string, params: Record<string, string>, key: string): Promise<Record<string, unknown>> {
  const wait = nextRequestAt - Date.now();
  if (wait > 0) await new Promise(resolve => setTimeout(resolve, wait));
  nextRequestAt = Date.now() + 1000;
  const url = new URL(path, 'https://restapi.amap.com');
  url.search = new URLSearchParams({ ...params, key }).toString();
  const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
  if (!response.ok) throw new AmapError(`高德服务返回 HTTP ${response.status}`);
  const data = record(await response.json());
  if (data.status !== '1') {
    const code = typeof data.infocode === 'string' && /^\d{5}$/.test(data.infocode) ? data.infocode : 'unknown';
    // Never log upstream info or a thrown fetch URL: either can contain the Key.
    throw new AmapError(`高德服务返回错误码 ${code}`);
  }
  return data;
}
export async function reverseAmap(lat: number, lng: number, key: string): Promise<{ address: string; details: LocationPlace }> {
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new AmapError('无效定位坐标');
  const converted = await request('/v3/assistant/coordinate/convert', { locations: `${lng},${lat}`, coordsys: 'gps', output: 'json' }, key);
  const parts = text(converted.locations)?.split(',');
  const coordinates = parts?.every(p => p.trim() !== '') ? parts.map(Number) : undefined;
  if (!coordinates || coordinates.length !== 2 || !coordinates.every(Number.isFinite) || Math.abs(coordinates[0]!) > 180 || Math.abs(coordinates[1]!) > 90)
    throw new AmapError('高德坐标转换未返回有效结果');
  const data = await request('/v3/geocode/regeo', { location: coordinates.join(','), extensions: 'all', radius: '200', output: 'json', homeorcorp: '0' }, key);
  const result = record(data.regeocode), address = text(result.formatted_address);
  if (!address) throw new AmapError('高德未返回有效地址');
  return { address, details: parseAmapPlace(result) };
}
export const amapErrorMessage = (error: unknown) => error instanceof AmapError ? error.message : '高德服务请求失败或超时';
