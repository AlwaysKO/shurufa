/** 独立的人为操作证据；服务器接收时间绝不能作为默认值。 */
export function parseDeviceInteraction(body: { last_interaction_at?: unknown; last_interaction_source?: unknown }, now = Date.now()): { at: string; source: string } | null | false {
  const at = body.last_interaction_at, source = body.last_interaction_source;
  if (at == null && source == null) return null;
  if (typeof at !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(at) ||
      typeof source !== 'string' || !['touch', 'key', 'ime_input', 'usage_interaction'].includes(source)) return false;
  const time = Date.parse(at);
  if (!Number.isFinite(time) || time <= 0 || time > now + 60_000) return false;
  // JS 日期解析会规范化不存在的日期，不能将2月30日当作有效操作。
  if (new Date(time).toISOString().slice(0, 19) !== at.slice(0, 19)) return false;
  return { at: new Date(time).toISOString(), source };
}
