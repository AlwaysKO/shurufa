import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
import * as evidence from '../src/usageEvidence';
it('真实事件时间与收到时间分离，补充交互不充当最后触摸', () => {
 const rows = evidence.usageEvidenceDetails({last_app_activity_at:'2026-10-15T02:08:00Z',last_app_activity_source:'usage_pause',last_app_activity_received_at:'2026-10-15T02:45:00Z',last_interaction_at:'2026-10-15T01:54:00Z',last_interaction_source:'usage_interaction'});
 expect(rows[0]).toEqual({label:'最近应用活动',at:'2026-10-15 10:08:00',source:'应用离开',received:'2026-10-15 10:45:00'});
 expect(rows[1].at).toBe('未知（未收到记录）');
 expect(rows[3].label).toBe('最近记录的交互');
 expect(rows[3].source).toBe('系统交互事件（非原始触摸）');
});
it('缺字段和无效时间不回填联系时间、当前时间或使用段', () => {
 expect(evidence.usageEvidenceDetails({}).every(row=>row.at==='未知（未收到记录）')).toBe(true);
 expect(evidence.evidenceTime('bad')).toBe('未知（未收到记录）');
 expect(evidence.evidenceSource('usage_keyguard_shown')).toBe('锁屏');
 expect(evidence.evidenceSource('usage_screen_off')).toBe('熄屏');
 expect(evidence.evidenceSource(undefined)).toBe('来源未知');
});
it('已授权交互源按实际触摸/按键范围命名，不扩大成全手机操作',()=>{
 expect(evidence.evidenceSource('touch')).toBe('触摸交互（监听范围内）');
 expect(evidence.evidenceSource('key')).toBe('按键交互（监听范围内）');
});
