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
export async function callAudio(device:string,id:string,signal:AbortSignal):Promise<Blob> {
  return (await checked(await dashboardFetch(url(device,`/${encodeURIComponent(id)}/audio`),{signal}))).blob();
}
export async function deleteCall(device:string,id:string):Promise<void> {
  await checked(await dashboardFetch(url(device,`/${encodeURIComponent(id)}`),{method:'DELETE'}));
}
