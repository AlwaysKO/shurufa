import { dashboardFetch } from '../auth';
import type { CaptureMutation } from '../captureManagement';
export function captureMutation(resource:'page-captures'|'video-visits'):CaptureMutation {
 return async(path,userId,body,method='POST')=>{
  const response=await dashboardFetch(`/api/v1/dashboard/${resource}/${path}?user_id=${encodeURIComponent(userId)}`,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  const result=await response.json().catch(()=>null);
  if(!response.ok)throw Error(typeof result?.error==='string'?result.error:`操作失败（${response.status}）`);
  return result;
 };
}
