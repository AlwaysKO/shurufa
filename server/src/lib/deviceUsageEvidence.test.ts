import { describe, expect, it } from 'vitest';
import { parseDeviceUsageEvidence } from './deviceUsageEvidence.js';
const device='00000000-0000-4000-8000-000000000001';
const version='00000000-0000-4000-8000-000000000002';
const now=Date.parse('2026-10-15T02:45:00Z');
const valid=()=>({version,device_id:device,app_activity:{at_ms:Date.parse('2026-10-15T02:08:00Z'),source:'usage_pause'},screen_off:{at_ms:Date.parse('2026-10-15T02:10:00Z'),source:'usage_screen_off'}});
describe('分类原始使用证据',()=>{
 it('旧客户端缺字段保持无证据',()=>expect(parseDeviceUsageEvidence(undefined,device,now)).toBeNull());
 it('保留10:08暂停和10:10熄屏，10:45补传不重写时间',()=>expect(parseDeviceUsageEvidence(valid(),device,now)).toEqual(valid()));
 it('支持独立锁屏原始事件',()=>expect(parseDeviceUsageEvidence({version,device_id:device,lock:{at_ms:now-1,source:'usage_keyguard_shown'}},device,now)).not.toBe(false));
 it.each([
  null,{}, {...valid(),device_id:version}, {...valid(),version:'bad'}, {...valid(),extra:1},
  {...valid(),screen_off:{at_ms:now+60_001,source:'usage_screen_off'}},
  {...valid(),screen_off:{at_ms:NaN,source:'usage_screen_off'}},
  {...valid(),screen_off:{at_ms:1,source:'usage_screen_off'}},
  {...valid(),screen_off:{at_ms:now,source:'usage_pause'}},
  {...valid(),app_activity:{at_ms:now,source:'heartbeat'}},
  {...valid(),screen_off:{at_ms:now,source:'usage_screen_off',device_id:version}},
  {version,device_id:device}, {...valid(),lock:null},
 ])('拒绝非法来源/时间/身份/空快照 %j',value=>expect(parseDeviceUsageEvidence(value,device,now)).toBe(false));
});
