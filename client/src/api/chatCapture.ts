export interface CaptureRule {id:string;packageName:string;minVersionCode:number;maxVersionCode:number|null;enabled:boolean;titleIds:string[];inputIds:string[];bodyIds:string[];backLabels:string[];settingsLabels:string[];voiceLabels:string[];voicePosition:'left'|'right'|'either'}
export interface CaptureConfig {schemaVersion:1;revision:number;rules:CaptureRule[]}
export interface CaptureState {current:CaptureConfig;history:CaptureConfig[]}

const integer=(n:unknown):n is number=>Number.isSafeInteger(n)&&Number(n)>=0;
const record=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v);
function defaultCaptureConfig():CaptureConfig {
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

export function parseCaptureRules(text:string,revision:number):CaptureRule[]{
 const rules:unknown=JSON.parse(text);
 if(!validCaptureRules(rules)||new TextEncoder().encode(JSON.stringify({schemaVersion:1,revision,rules})).length>32768)throw Error('规则不合法：请核对固定字段、包名、版本范围、唯一 id、数组最多16项与总大小32KB；禁止正则/脚本/通配。');
 return rules;
}
export interface CaptureDiagnostic {device_id:string;platform:string;app_version_code:number;app_version_name:string;config_revision:number;stage:string;status:string;error_code:number|null;observed_at:number;received_at:number}
export interface CaptureDiagnostics {device_id:string;platforms:Record<string,Record<string,CaptureDiagnostic|null>>}
/** Display recency only; a missing or historical result is not proof that capture failed. */
export function diagnosticFreshness(receivedAt:unknown,now:number):{state:'missing'|'recent'|'historical'|'invalid';ageMs:number|null;text:string}{
 if(receivedAt===null||receivedAt===undefined)return {state:'missing',ageMs:null,text:'未上报'};
 if(!Number.isSafeInteger(receivedAt)||Number(receivedAt)<=0||!Number.isSafeInteger(now)||now<=0||Number(receivedAt)>now)return {state:'invalid',ageMs:null,text:'接收时间无效或位于未来，仅作历史参考，不能判定当前正常'};
 const ageMs=now-Number(receivedAt),recent=ageMs<=600_000;
 const elapsed=ageMs<60_000?`${Math.floor(ageMs/1000)} 秒`:ageMs<3_600_000?`${Math.floor(ageMs/60_000)} 分钟`:ageMs<86_400_000?`${Math.floor(ageMs/3_600_000)} 小时`:`${Math.floor(ageMs/86_400_000)} 天`;
 return {state:recent?'recent':'historical',ageMs,text:`${recent?'近期结果（不代表当前正常）':'已过期，仅历史结果'} · 距最近接收 ${elapsed}`};
}
