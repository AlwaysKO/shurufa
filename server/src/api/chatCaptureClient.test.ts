import { expect,it } from 'vitest';
import { readFileSync } from 'node:fs';
const clientModule=new URL('../../../client/src/api/chatCapture.ts',import.meta.url).href;
import { defaultCaptureConfig } from '../lib/chatCaptureConfig.js';
it('后台规则编辑器与服务器约束一致',async()=>{const {parseCaptureRules}=await import(clientModule);const rules=defaultCaptureConfig().rules;expect(parseCaptureRules(JSON.stringify(rules),0)).toEqual(rules);for(const patch of [{titleIds:['.*']},{voicePosition:'bottom'},{enabled:1},{voiceLabels:['']},{bodyIds:['com.tencent.mobileqq:id/x']}]){const next=structuredClone(rules);Object.assign(next[0],patch);expect(()=>parseCaptureRules(JSON.stringify(next),0)).toThrow();}});
it('菜单路由和无诊断说明随源码管理',()=>{const view=readFileSync(new URL('../../../client/src/views/ChatCaptureSettings.vue',import.meta.url),'utf8');expect(view).toContain('未上报');expect(view).toContain('旧版');expect(readFileSync(new URL('../../../client/src/main.ts',import.meta.url),'utf8')).toContain("path: '/chat-capture-settings'");expect(readFileSync(new URL('../../../client/src/App.vue',import.meta.url),'utf8')).toContain("path: '/chat-capture-settings'");});

it('清空设备会清除诊断加载状态且编辑器说明规则顺序',()=>{const view=readFileSync(new URL('../../../client/src/views/ChatCaptureSettings.vue',import.meta.url),'utf8');expect(view).toContain('if(!currentUserId.value){diagnosticBusy.value=false;return;}');expect(view).toContain('第一条启用');expect(view).toContain('默认规则之前');});
it('诊断新鲜度区分未上报、十分钟边界、历史、未来和非法时间',async()=>{
 const {diagnosticFreshness}=await import(clientModule);expect(typeof diagnosticFreshness).toBe('function');
 const now=1_000_000;
 expect(diagnosticFreshness(null,now)).toMatchObject({state:'missing',ageMs:null});
 expect(diagnosticFreshness(now,now)).toMatchObject({state:'recent',ageMs:0});
 expect(diagnosticFreshness(now-600_000,now)).toMatchObject({state:'recent',ageMs:600_000});
 expect(diagnosticFreshness(now-600_001,now)).toMatchObject({state:'historical',ageMs:600_001});
 for(const t of [now+1,NaN,Infinity,-1,0,1.5,'999'])expect(diagnosticFreshness(t,now)).toMatchObject({state:'invalid',ageMs:null});
 expect(diagnosticFreshness(now,NaN)).toMatchObject({state:'invalid',ageMs:null});
});

it.each([{packageName:['com.tencent.mm']},{voicePosition:['either']},{packageName:{toString:'com.tencent.mm'}},{voicePosition:{toString:'either'}}])('后台编辑器严格拒绝非字符串枚举 %j',async patch=>{const {parseCaptureRules}=await import(clientModule);const rules=defaultCaptureConfig().rules;Object.assign(rules[0],patch);expect(()=>parseCaptureRules(JSON.stringify(rules),0)).toThrow();});
