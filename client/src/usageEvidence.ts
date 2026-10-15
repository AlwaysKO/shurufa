import type { DeviceRow } from './api';

export function evidenceTime(value: string | null | undefined) {
  if (!value || !Number.isFinite(Date.parse(value))) return '未知（未收到记录）';
  return new Date(value).toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
}
export function evidenceSource(value: string | null | undefined) {
  const sources: Record<string, string> = { usage_resume: '应用进入', usage_pause: '应用离开', usage_screen_off: '熄屏', usage_keyguard_shown: '锁屏', usage_interaction: '系统交互事件（非原始触摸）', ime_input: '输入法交互', touch: '触摸交互（监听范围内）', key: '按键交互（监听范围内）' };
  return value ? (sources[value] ?? `其他来源：${value}`) : '来源未知';
}
/** 只显示服务端收到的原始事件证据，不推断手机端待传状态。 */
export function usageEvidenceDetails(user: Partial<DeviceRow>) {
  return [
    { label: '最近应用活动', at: user.last_app_activity_at, source: user.last_app_activity_source, received: user.last_app_activity_received_at },
    { label: '最近熄屏', at: user.last_screen_off_at, source: user.last_screen_off_source, received: user.last_screen_off_received_at },
    { label: '最近锁屏', at: user.last_lock_at, source: user.last_lock_source, received: user.last_lock_received_at },
    { label: '最近记录的交互', at: user.last_interaction_at, source: user.last_interaction_source, received: null },
  ].map(row => ({ label: row.label, at: evidenceTime(row.at), source: evidenceSource(row.source), received: evidenceTime(row.received) }));
}
