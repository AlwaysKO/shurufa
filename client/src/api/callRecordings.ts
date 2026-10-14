import { dashboardFetch } from '../auth';
export interface CallRecording {
  record_id:string; platform:'phone'|'wechat'; call_type:'voice'|'video'; counterpart_display:string|null;
  call_started_at:number|null;call_ended_at:number|null;call_duration_ms:number|null;call_duration_estimated:boolean;
  recording_started_at:number;recording_ended_at:number;audio_duration_ms:number;
  recording_status:string;quality_status:string;failure_reason:string|null;upload_status:'saved';stored_at:string;
}
export interface CallFilter {from:string;to:string;platform:string;recording_status:string;page:number}
function url(device:string,suffix='') {return `/api/v1/dashboard/call-recordings${suffix}?user_id=${encodeURIComponent(device)}`;}
async function checked(response:Response) {
  if(!response.ok)throw new Error(response.status===401?'登录已过期':response.status===404?'录音不存在或已删除':`请求失败（${response.status}），请稍后重试`);
  return response;
}
export async function listCalls(device:string,filter:CallFilter,signal:AbortSignal):Promise<{records:CallRecording[];total:number}> {
  const params=new URLSearchParams();
  for(const [key,value] of Object.entries(filter))if(value!=='')params.set(key,String(value));
  return (await checked(await dashboardFetch(url(device)+'&'+params,{signal}))).json();
}
// 同源 Cookie 由浏览器携带，服务端仍逐次鉴权；不生成公开音频链接。
export function callAudioUrl(device:string,id:string):string {
  return url(device,`/${encodeURIComponent(id)}/audio`);
}
export async function checkCallAudio(device:string,id:string,signal:AbortSignal):Promise<Response> {
  return dashboardFetch(callAudioUrl(device,id),{method:'HEAD',signal});
}
export async function excludeCallAudio(device:string,id:string,reason:'decode_error'|'unsupported_format',signal:AbortSignal):Promise<void> {
  await checked(await dashboardFetch(url(device,`/${encodeURIComponent(id)}/playback-failure`),{
    method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({reason}),signal,
  }));
}
export async function deleteCall(device:string,id:string):Promise<void> {
  await checked(await dashboardFetch(url(device,`/${encodeURIComponent(id)}`),{method:'DELETE'}));
}
export interface PhoneCallLog {source_id:string;number:string|null;name:string|null;type:number;date:number;duration_seconds:number}
export interface PhoneCallLogSync {request_id:string|null;requested_at:string|null;status:'never'|'pending'|'synced'|'permission_required'|'disabled'|'failed';synced_at:string|null;truncated:boolean}
export async function listPhoneCallLogs(device:string,page:number,signal:AbortSignal):Promise<{records:PhoneCallLog[];total:number;sync:PhoneCallLogSync}> {
  return (await checked(await dashboardFetch(url(device,'/call-log')+'&page='+page,{signal}))).json();
}
export async function requestPhoneCallLogs(device:string,signal:AbortSignal):Promise<void> {
  await checked(await dashboardFetch(url(device,'/call-log/sync'),{method:'POST',signal}));
}
