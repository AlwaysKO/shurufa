import type pg from 'pg';

export const DEFAULT_COLLECTOR_BASE_URL = 'https://my.dog8ball.com';
const COLLECTOR_BASE_URL_KEY = 'collector_base_url';

export function normalizeCollectorBaseUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value.trim());
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) return null;
    if (url.pathname !== '/' && url.pathname !== '') return null;
    return url.origin;
  } catch { return null; }
}

export async function collectorBaseUrl(pool: pg.Pool): Promise<string> {
  const result = await pool.query<{ value: string }>('SELECT value FROM runtime_setting WHERE key=$1', [COLLECTOR_BASE_URL_KEY]);
  return normalizeCollectorBaseUrl(result.rows[0]?.value)
    ?? normalizeCollectorBaseUrl(process.env.COLLECTOR_PUBLIC_BASE_URL)
    ?? DEFAULT_COLLECTOR_BASE_URL;
}

export async function saveCollectorBaseUrl(pool: pg.Pool, value: string): Promise<string | null> {
  const normalized = normalizeCollectorBaseUrl(value);
  if (!normalized) return null;
  await pool.query(
    `INSERT INTO runtime_setting(key,value,updated_at) VALUES($1,$2,NOW())
     ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value,updated_at=NOW()`,
    [COLLECTOR_BASE_URL_KEY, normalized],
  );
  return normalized;
}

