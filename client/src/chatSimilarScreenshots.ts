import type { ChatMessageRow } from './api';

export interface ScreenshotGroup { key: string; messages: ChatMessageRow[] }
const bitCounts = [0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4];
function distance(a: string, b: string): number {
  return [...a].reduce((total, digit, index) => total + bitCounts[parseInt(digit, 16) ^ parseInt(b[index], 16)], 0);
}
function evidence(message: ChatMessageRow) {
  // 截图通常无正文；旧占位文案沿用截图删除规则。未知文字必须保持独立可见。
  if (!['', '图片', '截图', '聊天截图'].includes(message.text ?? '')) return null;
  if (!message.device_id || !message.conversation_id || message.message_type !== 'image' || message.assets.length !== 1
    || !(message.metadata.capture_kind === 'conversation_screenshot' || message.metadata.capture_source === 'wechat_empty_tree_screenshot')) return null;
  const asset = message.assets[0], hash = asset.perceptual_hash;
  if (!asset.mime_type.startsWith('image/') || !asset.width || !asset.height || asset.width <= 0 || asset.height <= 0
    || typeof hash !== 'string' || !/^[a-f0-9]{16}$/i.test(hash)) return null;
  const bits = distance(hash, '0000000000000000'), time = Date.parse(message.captured_at);
  // 空白或退化的低信息哈希不足以提示截图相似。
  if (bits < 8 || bits > 56 || !Number.isFinite(time)) return null;
  return { hash, time, width: asset.width, height: asset.height };
}
function similar(first: ChatMessageRow, previous: ChatMessageRow, current: ChatMessageRow): boolean {
  const a = evidence(first), b = evidence(previous), c = evidence(current);
  if (!a || !b || !c || first.device_id !== current.device_id || first.conversation_id !== current.conversation_id
    || first.platform !== current.platform || a.width !== c.width || a.height !== c.height) return false;
  return b.time > c.time && a.time - c.time <= 60_000 && distance(a.hash, c.hash) <= 2 && distance(b.hash, c.hash) <= 2;
}
/** 只提示当前原始页内连续截图相似；不参与采集、删除、存储或分页计数。 */
export function groupSimilarScreenshots(messages: ChatMessageRow[]): ScreenshotGroup[] {
  const groups: ScreenshotGroup[] = [];
  for (const message of messages) {
    const last = groups[groups.length - 1];
    if (last && similar(last.messages[0], last.messages[last.messages.length - 1], message)) last.messages.push(message);
    else groups.push({ key: `${message.id}:${message.assets[0]?.id ?? 'text'}`, messages: [message] });
  }
  return groups;
}
