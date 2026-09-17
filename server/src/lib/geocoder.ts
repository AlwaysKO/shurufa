import type pg from 'pg';

type Coordinate = { id: string | number; lat: number; lng: number };
type Result = { address: string | null; attempts: number; retryAt: number; error?: string };
type Job = { task?: Promise<void>; error?: string; retryAt: number };
export type AddressResolution = {
  address_status: 'pending' | 'resolving' | 'failed';
  address_error: string | null;
  address_retry_at: string | null;
};

// 成功结果复用；失败仅在冷却期缓存，不永久跳过。进程重启后由数据库地址兜底。
const cache = new Map<string, Result>();
const inFlight = new Map<string, Promise<Result>>();
const jobs = new WeakMap<pg.Pool, Map<string, Job>>();
let queue: Promise<unknown> = Promise.resolve();
let nextRequestAt = 0;
// 页面自动更新属于重复任务，保守限制为单进程每分钟最多4次；不同页面共用队列。
const REQUEST_INTERVAL = 15_000;
const RETRY_DELAY = 30_000;

/** 坐标去重键：round 4 位 ≈ 11 米，与服务端去重口径一致 */
export const locationKey = (lat: number, lng: number): string =>
  `${lat.toFixed(4)},${lng.toFixed(4)}`;

function poolJobs(pool: pg.Pool): Map<string, Job> {
  let states = jobs.get(pool);
  if (!states) { states = new Map(); jobs.set(pool, states); }
  return states;
}

function failed(attempts: number, error: string, retryAfter = 0): Result {
  const retryAt = Date.now() + Math.max(Math.min(RETRY_DELAY * 2 ** Math.min(attempts - 1, 4), 300_000), retryAfter);
  // 服务不可用时也暂停其他坐标，避免一批位置反复冲击上游。
  nextRequestAt = Math.max(nextRequestAt, retryAt);
  console.warn(`[geocoder] ${error}`); // 不记录用户坐标、地址或完整URL。
  return { address: null, attempts, retryAt, error };
}

async function reverseOne(lat: number, lng: number, attempts: number): Promise<Result> {
  try {
    const url = new URL(process.env.GEOCODER_REVERSE_URL || 'https://nominatim.openstreetmap.org/reverse');
    url.searchParams.set('lat', String(lat)); url.searchParams.set('lon', String(lng));
    url.searchParams.set('format', 'jsonv2'); url.searchParams.set('zoom', '16');
    url.searchParams.set('accept-language', 'zh-CN');
    const res = await fetch(url, {
      headers: { 'User-Agent': 'personal-ime-tracker/0.1' }, signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) {
      const value = res.headers.get('retry-after');
      const delay = value == null ? 0 : /^\d+$/.test(value) ? Number(value) * 1000 : Date.parse(value) - Date.now();
      return failed(attempts, `地址服务返回 HTTP ${res.status}`, Number.isFinite(delay) ? Math.max(0, delay) : 0);
    }
    const data = (await res.json()) as { display_name?: string; address?: Record<string, string> };
    if (!data.display_name) return failed(attempts, '地址服务未返回有效地址');
    const a = data.address ?? {};
    const parts = [a.country, a.state ?? a.province, a.city ?? a.county ?? a.town, a.suburb ?? a.neighbourhood, a.road, a.house_number];
    return { address: parts.filter(Boolean).join(' ') || data.display_name, attempts: 0, retryAt: 0 };
  } catch {
    return failed(attempts, '地址服务请求失败或超时');
  }
}

function lookup(c: Coordinate): Promise<Result> {
  const key = locationKey(c.lat, c.lng);
  const cached = cache.get(key);
  if (cached && (cached.address || cached.retryAt > Date.now())) return Promise.resolve(cached);
  const pending = inFlight.get(key);
  if (pending) return pending;
  const task = queue.then(async () => {
    const wait = nextRequestAt - Date.now();
    if (wait > 0) await new Promise(resolve => setTimeout(resolve, wait));
    nextRequestAt = Date.now() + REQUEST_INTERVAL;
    const result = await reverseOne(c.lat, c.lng, (cached?.attempts ?? 0) + 1);
    cache.set(key, result);
    return result;
  });
  inFlight.set(key, task);
  queue = task.then(() => { inFlight.delete(key); }, () => { inFlight.delete(key); });
  return task;
}

/** 状态只反映当前服务进程；已有地址由接口直接标记resolved。 */
export function addressResolution(pool: pg.Pool, userId: string, c: Coordinate): AddressResolution {
  const job = poolJobs(pool).get(`${userId}:${c.id}`);
  if (job?.task) return { address_status: 'resolving', address_error: null, address_retry_at: null };
  const result = job?.error ? job : cache.get(locationKey(c.lat, c.lng));
  if (result?.error) return { address_status: 'failed', address_error: result.error, address_retry_at: new Date(result.retryAt).toISOString() };
  return { address_status: 'pending', address_error: null, address_retry_at: null };
}

/** 合并并发请求，只补齐本次选中、当前用户的空地址，不改坐标/时间/已有地址。 */
export async function resolveMissingAddresses(pool: pg.Pool, coords: Coordinate[], userId: string): Promise<void> {
  const states = poolJobs(pool);
  await Promise.all(coords.map(c => {
    const id = `${userId}:${c.id}`;
    const existing = states.get(id);
    if (existing?.task) return existing.task;
    if (existing && existing.retryAt > Date.now()) return;
    const cached = cache.get(locationKey(c.lat, c.lng));
    if (cached && !cached.address && cached.retryAt > Date.now()) return;
    const job: Job = { retryAt: 0 };
    states.set(id, job);
    job.task = (async () => {
      try {
        const result = await lookup(c);
        if (!result.address) {
          job.error = result.error;
          job.retryAt = result.retryAt;
          return;
        }
        await pool.query(
          'UPDATE location_track SET address = $1 WHERE id = $2 AND user_id = $3 AND address IS NULL',
          [result.address, c.id, userId],
        );
        states.delete(id);
      } catch {
        job.error = '地址已获取，但保存失败';
        job.retryAt = Date.now() + RETRY_DELAY;
        console.warn('[geocoder] 地址回写失败，将稍后重试');
      } finally { job.task = undefined; }
    })();
    return job.task;
  }));
}
