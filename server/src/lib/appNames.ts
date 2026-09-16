import type pg from 'pg';
import type { MobileEvent } from '../types/events.js';

/** 可选展示字段不得破坏事件入库；不接受对象、超长文本或纯空白。 */
export function normalizeAppName(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const name = value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, '').trim();
  return name.length > 0 && name.length <= 120 ? name : null;
}

export function eventMetadata(event: MobileEvent): Record<string, unknown> {
  const metadata = event.metadata && typeof event.metadata === 'object' && !Array.isArray(event.metadata)
    ? { ...event.metadata } : {};
  const name = normalizeAppName(event.app_name) ?? normalizeAppName(metadata.app_name);
  if (name && event.package_name && name !== event.package_name) metadata.app_name = name;
  else delete metadata.app_name;
  return metadata;
}

/** 名称查找不受报表日期限制，让同一用户已上报的名称也用于历史统计。 */
export async function withAppNames<T extends { package_name: string }>(
  pool: pg.Pool, userId: string, rows: T[],
): Promise<Array<T & { app_name: string | null }>> {
  if (rows.length === 0) return [];
  const names = await pool.query<{ package_name: string; app_name: string }>(
    `SELECT DISTINCT ON (package_name) package_name, metadata->>'app_name' AS app_name
     FROM input_event
     WHERE user_id = $1 AND package_name = ANY($2::text[])
       AND jsonb_typeof(metadata->'app_name') = 'string'
       AND length(btrim(metadata->>'app_name')) BETWEEN 1 AND 120
       AND metadata->>'app_name' <> package_name
     ORDER BY package_name, occurred_at DESC, id DESC`,
    [userId, rows.map(row => row.package_name)],
  );
  const byPackage = new Map(names.rows.map(row => [row.package_name, normalizeAppName(row.app_name)]));
  return rows.map(row => ({ ...row, app_name: byPackage.get(row.package_name) ?? null }));
}
