import { expect, it } from 'vitest';
import { summarizeEditGroup } from './groupedEdits.js';
const row = (sequence_no: number, text_before: string | null, text_after: string | null, extra = {}) => ({
  id: String(sequence_no), user_id: 'u', device_id: 'd', package_name: 'chat', editor_id: 'editor', session_id: 'session',
  sequence_no, occurred_at: new Date(sequence_no * 1000), event_type: 'commit', text: '原始文字', text_before, text_after,
  metadata: { edit_protocol: 1, snapshot_complete: true }, ...extra,
});
it('按可靠序号汇总而不拼接原始片段，保存删除与清空', () => {
  const result = summarizeEditGroup([row(3, '九', ''), row(1, '', '八'), row(2, '八', '九', { event_type: 'delete', text: '八' })]);
  expect(result).toMatchObject({ edit_count: 3, edit_complete: true, text_after: '', text: '原始文字' });
  expect(result.edit_events.map(x => x.sequence_no)).toEqual([1, 2, 3]);
  expect(result.edit_events[1].text).toBe('八');
});
it.each([
  [row(2, '', 'a')], [row(1, '', 'a'), row(3, 'a', 'b')],
  [row(1, '', 'a'), row(2, 'wrong', 'b')], [row(1, null, 'a')],
  [row(1, '', 'a', { metadata: { edit_protocol: 1, snapshot_complete: false } })],
  [row(1, '', 'a', { metadata: { edit_protocol: '1', snapshot_complete: true } })],
  [row(1, '', 'a', { event_type: 'compose' })], [row(1, '', 'a'), row(1, 'a', 'b')],
  [row(1, '', 'a'), row(2, 'a', 'b', { device_id: 'other' })],
])('有缺失、断链、伪协议或未知事件不声称完整：%j', (...rows) => {
  expect(summarizeEditGroup(rows).edit_complete).toBe(false);
});
