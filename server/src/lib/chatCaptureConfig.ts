import type pg from 'pg';
export interface CaptureRule {id:string;packageName:string;minVersionCode:number;maxVersionCode:number|null;enabled:boolean;titleIds:string[];inputIds:string[];bodyIds:string[];backLabels:string[];settingsLabels:string[];voiceLabels:string[];voicePosition:'left'|'right'|'either'}
export interface CaptureConfig {schemaVersion:1;revision:number;rules:CaptureRule[]}
export interface CaptureState {current:CaptureConfig;history:CaptureConfig[]}
const KEY='chat_capture_v1';
const integer=(n:unknown):n is number=>Number.isSafeInteger(n)&&Number(n)>=0;
const record=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v);
export function defaultCaptureConfig():CaptureConfig {
 const common={minVersionCode:0,maxVersionCode:null,enabled:true,backLabels:['返回','返回上一页','Back'],settingsLabels:['聊天设置','聊天详情','会话设置','群聊设置','群聊详情','更多'],voiceLabels:['切换到语音输入','切换到语音','按住说话','语音'],voicePosition:'either' as const};
 return {schemaVersion:1,revision:0,rules:[{...common,id:'wechat-chat',packageName:'com.tencent.mm',titleIds:['chatting_title'],inputIds:['chatting_content_et','chat_input'],bodyIds:[]},{...common,id:'douyin-chat',packageName:'com.ss.android.ugc.aweme',titleIds:['vw3','vww'],inputIds:['msg_et'],bodyIds:['jta','v6q']}]};
}
export function validCaptureRules(value:unknown):value is CaptureRule[] {
 if(!Array.isArray(value)||value.length>20)return false;
 const ids=new Set<string>(),fields=Object.keys(defaultCaptureConfig().rules[0]).sort().join(',');
 return value.every(r=>{
  if(!record(r)||Object.keys(r).sort().join(',')!==fields||typeof r.id!=='string'||!/^[a-z][a-z0-9_-]{0,63}$/.test(r.id)||ids.has(r.id))return false;ids.add(r.id);
  if(typeof r.packageName!=='string'||!['com.tencent.mm','com.ss.android.ugc.aweme'].includes(r.packageName)||!integer(r.minVersionCode)||(r.maxVersionCode!==null&&(!integer(r.maxVersionCode)||r.maxVersionCode<r.minVersionCode))||typeof r.enabled!=='boolean'||typeof r.voicePosition!=='string'||!['left','right','either'].includes(r.voicePosition))return false;
  return ['titleIds','inputIds','bodyIds','backLabels','settingsLabels','voiceLabels'].every(field=>{const a=r[field];if(!Array.isArray(a)||a.length>16||new Set(a).size!==a.length)return false;return a.every(v=>typeof v==='string'&&v.trim().length>0&&v.trim()===v&&!/[\x00-\x1f\x7f]/.test(v)&&(field.endsWith('Ids') ? /^[A-Za-z0-9_.=-]{1,128}$/.test(v)||v.startsWith(`${r.packageName}:id/`)&&/^[A-Za-z0-9_.=-]{1,128}$/.test(v.slice(`${r.packageName}:id/`.length)) : Array.from(v).length<=64&&!/[\*\[\]{}\\^$|]/.test(v)));});
 });
}
function validConfig(v: unknown): v is CaptureConfig { return record(v) && Object.keys(v).sort().join(',')==='revision,rules,schemaVersion' && v.schemaVersion === 1 && integer(v.revision) && validCaptureRules(v.rules) && Buffer.byteLength(JSON.stringify(v)) <= 32768; }
function parseState(raw?: string): CaptureState {
  if (raw === undefined) return { current: defaultCaptureConfig(), history: [] };
  const v: unknown = JSON.parse(raw);
  if (!record(v) || !validConfig(v.current) || !Array.isArray(v.history) || v.history.length > 20 || !v.history.every(validConfig)) throw new Error('invalid stored chat capture config');
  return v as unknown as CaptureState;
}
export async function readCaptureState(pool: pg.Pool): Promise<CaptureState> {
  const result = await pool.query<{value:string}>('SELECT value FROM runtime_setting WHERE key=$1', [KEY]);
  return parseState(result.rows[0]?.value);
}
export class CaptureConflict extends Error {}
export class CaptureValidation extends Error {}
/** One complete state row, atomically compared to the exact previously read value: no process-local lock. */
export async function updateCaptureState(pool: pg.Pool, expectedRevision: number, rules?: CaptureRule[], rollbackRevision?: number): Promise<CaptureState> {
  const result = await pool.query<{value:string}>('SELECT value FROM runtime_setting WHERE key=$1', [KEY]);
  const raw = result.rows[0]?.value;
  const old = parseState(raw);
  if (old.current.revision !== expectedRevision) throw new CaptureConflict();
  const selected = rollbackRevision === undefined ? rules : [old.current,...old.history].find(c => c.revision === rollbackRevision)?.rules;
  if (!validCaptureRules(selected) || !Number.isSafeInteger(expectedRevision + 1)) throw new CaptureValidation();
  const next: CaptureState = { current: { schemaVersion: 1, revision: expectedRevision + 1, rules: selected }, history: [old.current,...old.history].slice(0,20) };
  if (Buffer.byteLength(JSON.stringify(next.current)) > 32768) throw new CaptureValidation();
  const saved = raw === undefined
    ? await pool.query('INSERT INTO runtime_setting(key,value,updated_at) VALUES($1,$2,NOW()) ON CONFLICT(key) DO NOTHING RETURNING key', [KEY,JSON.stringify(next)])
    : await pool.query('UPDATE runtime_setting SET value=$2,updated_at=NOW() WHERE key=$1 AND value=$3 RETURNING key', [KEY,JSON.stringify(next),raw]);
  if (!saved.rowCount) throw new CaptureConflict();
  return next;
}
