import type pg from 'pg';
export const DELIVERY_PACKAGES = ['com.tencent.mm', 'com.tencent.mobileqq', 'com.ss.android.ugc.aweme'] as const;
export const DELIVERY_MIMES = ['image/gif', 'image/webp', 'image/png', 'image/jpeg'];
export interface DeliveryRule {
  id: string; packageName: string; mimeTypes: string[]; minVersionCode: number; maxVersionCode: number | null;
  versionName: string | null; minSdk: number; enabled: boolean; method: 'commit_content' | 'private_command';
  requireCompatIme: boolean; requiredEditorExtras: Record<string, number>; action: string | null; uriKey: string | null;
}
export interface DeliveryConfig { schemaVersion: 1; revision: number; rules: DeliveryRule[] }
export interface DeliveryState { current: DeliveryConfig; history: DeliveryConfig[] }
const KEY = 'expression_delivery_v1';
const identifier = /^[A-Za-z_][A-Za-z0-9_.]*$/;
const integer = (n: unknown): n is number => Number.isSafeInteger(n) && Number(n) >= 0;
const record = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
export function defaultDeliveryConfig(): DeliveryConfig {
  const standard = (id: string, packageName: string, mimeTypes = [...DELIVERY_MIMES]): DeliveryRule => ({ id, packageName, mimeTypes, minVersionCode: 0, maxVersionCode: null, versionName: null, minSdk: 23, enabled: true, method: 'commit_content', requireCompatIme: false, requiredEditorExtras: {}, action: null, uriKey: null });
  return { schemaVersion: 1, revision: 0, rules: [
    { ...standard('wechat-gif', DELIVERY_PACKAGES[0], ['image/gif']), versionName: '8.0.78', minSdk: 26, method: 'private_command', requireCompatIme: true, requiredEditorExtras: { SUPPORT_SOGOU_EXPRESSION: 1 }, action: 'com.sogou.inputmethod.exp.commit', uriKey: 'EXP_PATH_URI' },
    standard('wechat-static', DELIVERY_PACKAGES[0], DELIVERY_MIMES.filter(m => m !== 'image/gif')),
    standard('qq-content', DELIVERY_PACKAGES[1]), standard('douyin-content', DELIVERY_PACKAGES[2]),
  ] };
}
export function validDeliveryRules(value: unknown): value is DeliveryRule[] {
  if (!Array.isArray(value) || value.length > 40) return false;
  const ids = new Set<string>();
  const fields = Object.keys(defaultDeliveryConfig().rules[0]).sort().join(',');
  return value.every(r => {
    if (!record(r) || Object.keys(r).sort().join(',') !== fields) return false;
    if (typeof r.id !== 'string' || !/^[a-z][a-z0-9_-]{0,63}$/.test(r.id) || ids.has(r.id)) return false;
    ids.add(r.id);
    if (!DELIVERY_PACKAGES.includes(r.packageName as any) || !Array.isArray(r.mimeTypes) || !r.mimeTypes.length || r.mimeTypes.length > 4 || new Set(r.mimeTypes).size !== r.mimeTypes.length || r.mimeTypes.some(m => !DELIVERY_MIMES.includes(m))) return false;
    if (!integer(r.minVersionCode) || (r.maxVersionCode !== null && (!integer(r.maxVersionCode) || r.maxVersionCode < r.minVersionCode))) return false;
    if (r.versionName !== null && (typeof r.versionName !== 'string' || !r.versionName.length || r.versionName.length > 80)) return false;
    if (!integer(r.minSdk) || r.minSdk < 23 || r.minSdk > 100 || typeof r.enabled !== 'boolean' || typeof r.requireCompatIme !== 'boolean') return false;
    if (!record(r.requiredEditorExtras) || Object.keys(r.requiredEditorExtras).length > 8 || Object.entries(r.requiredEditorExtras).some(([k,v]) => k.length > 160 || !identifier.test(k) || !Number.isInteger(v) || Number(v) < -2147483648 || Number(v) > 2147483647)) return false;
    if (r.method === 'commit_content') {
      // 已验证微信 8.0.78 标准交付会静态化；不得全版本盲放 GIF。
      if (r.packageName === 'com.tencent.mm' && r.mimeTypes.includes('image/gif') && (r.versionName === null || r.versionName === '8.0.78')) return false;
      return r.action === null && r.uriKey === null;
    }
    return r.method === 'private_command' && r.minSdk >= 26 && typeof r.action === 'string' && r.action.length <= 160 && identifier.test(r.action) && typeof r.uriKey === 'string' && r.uriKey.length <= 80 && identifier.test(r.uriKey);
  });
}
function validConfig(v: unknown): v is DeliveryConfig { return record(v) && v.schemaVersion === 1 && integer(v.revision) && validDeliveryRules(v.rules) && Buffer.byteLength(JSON.stringify(v)) <= 65536; }
function parseState(raw?: string): DeliveryState {
  if (raw === undefined) return { current: defaultDeliveryConfig(), history: [] };
  const v: unknown = JSON.parse(raw);
  if (!record(v) || !validConfig(v.current) || !Array.isArray(v.history) || v.history.length > 20 || !v.history.every(validConfig)) throw new Error('invalid stored expression delivery config');
  return v as unknown as DeliveryState;
}
export async function readDeliveryState(pool: pg.Pool): Promise<DeliveryState> {
  const result = await pool.query<{value:string}>('SELECT value FROM runtime_setting WHERE key=$1', [KEY]);
  return parseState(result.rows[0]?.value);
}
export class DeliveryConflict extends Error {}
export class DeliveryValidation extends Error {}
/** One complete state row, atomically compared to the exact previously read value: no process-local lock. */
export async function updateDeliveryState(pool: pg.Pool, expectedRevision: number, rules?: DeliveryRule[], rollbackRevision?: number): Promise<DeliveryState> {
  const result = await pool.query<{value:string}>('SELECT value FROM runtime_setting WHERE key=$1', [KEY]);
  const raw = result.rows[0]?.value;
  const old = parseState(raw);
  if (old.current.revision !== expectedRevision) throw new DeliveryConflict();
  const selected = rollbackRevision === undefined ? rules : [old.current,...old.history].find(c => c.revision === rollbackRevision)?.rules;
  if (!validDeliveryRules(selected) || !Number.isSafeInteger(expectedRevision + 1)) throw new DeliveryValidation();
  const next: DeliveryState = { current: { schemaVersion: 1, revision: expectedRevision + 1, rules: selected }, history: [old.current,...old.history].slice(0,20) };
  if (Buffer.byteLength(JSON.stringify(next.current)) > 65536) throw new DeliveryValidation();
  const saved = raw === undefined
    ? await pool.query('INSERT INTO runtime_setting(key,value,updated_at) VALUES($1,$2,NOW()) ON CONFLICT(key) DO NOTHING RETURNING key', [KEY,JSON.stringify(next)])
    : await pool.query('UPDATE runtime_setting SET value=$2,updated_at=NOW() WHERE key=$1 AND value=$3 RETURNING key', [KEY,JSON.stringify(next),raw]);
  if (!saved.rowCount) throw new DeliveryConflict();
  return next;
}
