import { dashboardFetch } from '../auth';
import { currentUserId } from './index';
export type DictionaryStatus = 'enabled' | 'disabled' | 'deleted';
export interface DictionaryDevice {
  device_id: string; group_id: string; name: string; model: string; brand: string; dashboard_name: string;
  in_group: boolean; synced: boolean; restore_enabled?: boolean; last_report_at: string | null; applied_at: string | null;
  migration_status: string; imported: number;
}
export interface DictionaryEntry {
  device_id: string; device_ids?: string[]; kind: string; text: string; code: string; pinyin: string; source: string;
  count: number; weight: number; last_used: number; status: DictionaryStatus;
}
async function call<T>(path: string, query: Record<string,string|number|undefined> = {}, body?: unknown): Promise<T> {
  const params = new URLSearchParams({user_id:currentUserId.value});
  for (const [key,value] of Object.entries(query)) if (value !== undefined && value !== '') params.set(key,String(value));
  const response = await dashboardFetch(`/api/v1/dashboard/dictionary/${path}?${params}`, body === undefined ? {} : {
    method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error ?? '词库请求失败');
  return data;
}
export const dictionaryApi = {
  devices: () => call<{devices:DictionaryDevice[]}>('devices'),
  entries: (query: {device_id?:string;q?:string;status?:string;page:number;view?:string}) => call<{entries:DictionaryEntry[];total:number;page:number}>('entries',query),
  bind: (device_id:string) => call('bind',{}, {device_id}),
  decisions: (texts:string[],status:DictionaryStatus) => call('decisions',{}, {texts,status}),
};
