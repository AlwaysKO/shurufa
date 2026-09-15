import type pg from 'pg';

/**
 * IP → 地理位置懒解析（ip-api.com 免费接口，非商用限 45 次/分钟）。
 * 仅对未解析过的 IP 发起请求，结果回写 input_event.ip_location 持久化缓存。
 */
const cache = new Map<string, { location: string | null; retryAt: number }>();
const pending = new Map<string, Promise<string | null>>();

async function resolveOne(ip: string): Promise<string | null> {
  if (ip === '::1' || ip === '127.0.0.1' || ip === 'localhost' || ip.startsWith('192.168.') || ip.startsWith('10.')) {
    return '本机/内网';
  }
  try {
    const res = await fetch(
      `http://ip-api.com/json/${encodeURIComponent(ip)}?lang=zh-CN&fields=status,country,regionName,city`,
      { signal: AbortSignal.timeout(5000) },
    );
    if (!res.ok) return null;
    const data = (await res.json()) as { status: string; country?: string; regionName?: string; city?: string };
    if (data.status !== 'success') return null;
    return [data.country, data.regionName, data.city].filter(Boolean).join(' ');
  } catch {
    return null;
  }
}

/** 同 IP 共享在途解析，成功结果优先从内存或已持久化的记录复用。 */
async function cachedLocation(pool: pg.Pool, ip: string): Promise<string | null> {
  const cached = cache.get(ip);
  if (cached && (cached.location || cached.retryAt > Date.now())) return cached.location;
  const inFlight = pending.get(ip);
  if (inFlight) return inFlight;

  const task = (async () => {
    const stored = await pool.query<{ ip_location: string }>(
      "SELECT ip_location FROM input_event WHERE client_ip = $1 AND ip_location IS NOT NULL AND ip_location <> '' LIMIT 1",
      [ip],
    );
    const location = stored.rows[0]?.ip_location || await resolveOne(ip) || null;
    cache.set(ip, { location, retryAt: location ? Infinity : Date.now() + 60_000 });
    return location;
  })();
  pending.set(ip, task);
  try {
    return await task;
  } finally {
    pending.delete(ip);
  }
}

/** 返回当前响应可用的地址，并为同 IP 新记录补写成功结果。 */
export async function resolveMissingIps(pool: pg.Pool, ips: string[]): Promise<Map<string, string | null>> {
  const unique = [...new Set(ips.filter(Boolean))];
  const entries = await Promise.all(unique.map(async (ip): Promise<[string, string | null]> => {
    const loc = await cachedLocation(pool, ip);
    if (loc) {
      await pool.query(
        "UPDATE input_event SET ip_location = $1 WHERE client_ip = $2 AND (ip_location IS NULL OR ip_location = '')",
        [loc, ip],
      );
    }
    return [ip, loc];
  }));
  return new Map(entries);
}
