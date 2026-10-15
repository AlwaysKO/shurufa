import type pg from 'pg';
import type { DeviceUsageEvidence } from '../types/events.js';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const categories = {
  app_activity: { column: 'last_app_activity', sources: ['usage_resume', 'usage_pause'] },
  screen_off: { column: 'last_screen_off', sources: ['usage_screen_off'] },
  lock: { column: 'last_lock', sources: ['usage_keyguard_shown'] },
} as const;
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
/** 只接收原始系统事件，不从使用段结束原因或采样时钟推断。undefined兼容旧客户端。 */
export function parseDeviceUsageEvidence(value: unknown, device: string, now = Date.now()): DeviceUsageEvidence | null | false {
  if (value === undefined) return null;
  if (!object(value) || typeof value.version !== 'string' || !UUID.test(value.version) ||
      typeof value.device_id !== 'string' || !UUID.test(value.device_id) || value.device_id.toLowerCase() !== device.toLowerCase() ||
      Object.keys(value).some(key => !['version', 'device_id', ...Object.keys(categories)].includes(key))) return false;
  let count = 0;
  for (const [key, spec] of Object.entries(categories)) {
    if (!(key in value)) continue;
    const event = value[key];
    if (!object(event) || Object.keys(event).length !== 2 || !Number.isSafeInteger(event.at_ms) ||
        (event.at_ms as number) < 946684800000 || (event.at_ms as number) > now + 60_000 ||
        typeof event.source !== 'string' || !(spec.sources as readonly string[]).includes(event.source)) return false;
    count++;
  }
  return count ? value as unknown as DeviceUsageEvidence : false;
}
/** 调用者须持有事务；同类同时间重试不刷新首次接收时刻。列名仅来自内部常量。 */
export async function saveDeviceUsageEvidence(db: pg.PoolClient, user: string, evidence: DeviceUsageEvidence): Promise<void> {
  for (const [key, { column }] of Object.entries(categories)) {
    const event = evidence[key as keyof typeof categories];
    if (!event) continue;
    await db.query(`UPDATE device SET ${column}_at=$2, ${column}_source=$3, ${column}_received_at=NOW()
      WHERE id=$1 AND (${column}_at IS NULL OR ${column}_at < $2::timestamptz)`,
    [user, new Date(event.at_ms).toISOString(), event.source]);
  }
  // 相同发生时间采用固定优先级：锁屏、熄屏、应用活动；不使用接收时间决胜。
  await db.query(`UPDATE device SET latest_usage_at=GREATEST(last_app_activity_at,last_screen_off_at,last_lock_at),
    latest_usage_source=CASE
      WHEN last_lock_at=GREATEST(last_app_activity_at,last_screen_off_at,last_lock_at) THEN last_lock_source
      WHEN last_screen_off_at=GREATEST(last_app_activity_at,last_screen_off_at,last_lock_at) THEN last_screen_off_source
      ELSE last_app_activity_source END WHERE id=$1`, [user]);
}
/** 目录与详情共享字段；所有字段为空时代表未收到证据，不是“已全部同步”。 */
export const usageEvidenceColumns = `latest_usage_at, latest_usage_source,
  last_app_activity_at, last_app_activity_source, last_app_activity_received_at,
  last_screen_off_at, last_screen_off_source, last_screen_off_received_at,
  last_lock_at, last_lock_source, last_lock_received_at`;
