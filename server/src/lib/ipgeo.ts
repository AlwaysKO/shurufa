import type pg from 'pg';

/**
 * IP → 地理位置懒解析（ip-api.com 免费接口，非商用限 45 次/分钟）。
 * 仅对未解析过的 IP 发起请求，结果回写 input_event.ip_location 持久化缓存。
 */
const cache = new Map<string, string | null>();

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

/** 解析一批未解析的 IP 并回写数据库（fire-and-forget，失败静默，不阻塞请求） */
export async function resolveMissingIps(pool: pg.Pool, ips: string[]): Promise<void> {
  const unique = [...new Set(ips.filter((ip) => ip && !cache.has(ip)))];
  for (const ip of unique) {
    const loc = await resolveOne(ip);
    cache.set(ip, loc);
    if (loc) {
      await pool
        .query('UPDATE input_event SET ip_location = $1 WHERE client_ip = $2 AND ip_location IS NULL', [loc, ip])
        .catch(() => {});
    }
  }
}
