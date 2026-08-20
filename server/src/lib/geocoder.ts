import type pg from 'pg';

/**
 * 坐标 → 地址反向地理编码（Nominatim / OpenStreetMap，免费无需 key，限 1 req/s）。
 * 仅对未解析过的坐标发起请求，结果回写 location_track.address 持久化缓存。
 */
const cache = new Map<string, string | null>();

/** 坐标去重键：round 4 位 ≈ 11 米，与服务端去重口径一致 */
export const locationKey = (lat: number, lng: number): string =>
  `${lat.toFixed(4)},${lng.toFixed(4)}`;

async function reverseOne(lat: number, lng: number): Promise<string | null> {
  try {
    const res = await fetch(
      `https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lng}&format=jsonv2&zoom=16&accept-language=zh-CN`,
      { headers: { 'User-Agent': 'personal-ime-tracker/0.1' }, signal: AbortSignal.timeout(8000) },
    );
    if (!res.ok) return null;
    const data = (await res.json()) as { display_name?: string; address?: Record<string, string> };
    if (!data.display_name) return null;
    // 精简：省/市/区 + 街道门牌（display_name 过长时取 address 关键字段）
    const a = data.address ?? {};
    const parts = [a.country, a.state ?? a.province, a.city ?? a.county ?? a.town, a.suburb ?? a.neighbourhood, a.road, a.house_number];
    return parts.filter(Boolean).join(' ') || data.display_name;
  } catch {
    return null;
  }
}

/** 解析一批未解析的坐标并回写数据库（fire-and-forget，失败静默） */
export async function resolveMissingAddresses(
  pool: pg.Pool,
  coords: Array<{ id: number; lat: number; lng: number }>,
): Promise<void> {
  const todo = coords.filter((c) => !cache.has(locationKey(c.lat, c.lng)));
  for (const c of todo) {
    const key = locationKey(c.lat, c.lng);
    const address = await reverseOne(c.lat, c.lng);
    cache.set(key, address);
    if (address) {
      await pool
        .query('UPDATE location_track SET address = $1 WHERE id = $2 AND address IS NULL', [address, c.id])
        .catch(() => {});
    }
  }
}
