// 全为虚构数据，长中文与无空格字段同时覆盖；不读取真实设备/API。
const userId = 'aaaaaaaa-0000-4000-8000-000000000001';
const long = '响应式虚构长内容'.repeat(12), pkg = 'com.fixture.' + 'longpackage'.repeat(12);
const stamp = '2026-10-14T08:00:00.000Z', ms = Date.parse(stamp);
const device = { id: userId, dashboard_name: '虚构手机', brand: 'Test', model: 'Fixture', last_seen_at: stamp };
const app = { package_name: pkg, app_name: long, input_chars: '123', event_count: '4', duration_ms: 60000, count: 1 };
const usage = { id: '1', kind: 'usage', ...app, start_ms: ms, end_ms: ms + 60000, duration_ms: 60000, end_reason: 'app_switch', clipped_start_ms: ms, clipped_end_ms: ms + 60000 };
const source = { typed: 100, pasted: 20, external: 2, voice: 1, total: 123 };
const location = { id: '1', device_id: userId, latitude: '31.23', longitude: '121.47', accuracy: '10', provider: 'gps', speed: '0', address: long, occurred_at: stamp, first_seen_at: stamp, last_seen_at: stamp, count: '1' };
const event = { id: '1', occurred_at: stamp, event_type: 'paste', content_type: 'text', text: long + pkg, input_code: null, package_name: pkg, device_id: userId, session_id: null, client_ip: '127.0.0.1', ip_location: long, network_type: 'wifi' };
const data = {
  '/api/v1/auth/session': { username: 'fixture' },
  '/api/v1/dashboard/users': { users: [device], total: 1, page: 1, page_size: 12 },
  '/api/v1/dashboard/devices': { devices: [device] },
  '/api/v1/dashboard/overview': { today: { input_events: '10', input_chars: '123', clipboard_count: '3', delete_count: '4' }, period: { active_days: '7' }, total_chars: '12345', source_distribution: source },
  '/api/v1/dashboard/timeline': { timeline: [{ day: '2026-10-14', input_chars: '123', event_count: '4' }] },
  '/api/v1/dashboard/hours': { hours: [{ hour: 8, input_chars: '123' }] },
  '/api/v1/dashboard/heatmap': { cells: [{ dow: 3, hour: 8, chars: '123' }] },
  '/api/v1/dashboard/apps': { apps: [app] },
  '/api/v1/dashboard/event-apps': { apps: [app] },
  '/api/v1/dashboard/user-phrases': { phrases: [{ id: 1, content: long + pkg, use_count: 5 }] },
  '/api/v1/dashboard/phrases': { phrases: [{ phrase: '虚构测试词', use_count: '10', use_days: '3', last_used_at: stamp }] },
  '/api/v1/dashboard/report': { summary: { input_events: '1', input_chars: '123', copy_count: '1', delete_count: '1', voice_count: '1', device_count: '1', active_days: '1' }, peak_hours: [{ hour: 8, chars: '123' }], source_distribution: source, top_apps: [app], top_phrases: [{ phrase: long + pkg, use_count: '10' }], top_locations: [location] },
  '/api/v1/dashboard/completions': { show_count: 10, accept_count: 5, accept_rate: 50, saved_chars: 10, candidate_count: 1, top: [{ prefix: pkg, completion: long, use_count: '5', show_count: '10', accept_count: '5', score: '1' }] },
  '/api/v1/dashboard/clipboard': { counts: { copy_count: '1', paste_count: '1' }, type_distribution: { plain: 1, url: 1, phone: 1, email: 1 }, paste_intervals: { within_10s: '1', within_1m: '1' }, top: [{ text: long + pkg, count: '1', last_used_at: stamp }] },
  '/api/v1/dashboard/events': { items: [event], total: 1, page: 1, page_size: 20 },
  '/api/v1/dashboard/app-usage': { overview: { duration_ms: 60000, count: 1, gap_count: 0, last_received_at: stamp }, apps: [app], daily: [{ day: '2026-10-14', duration_ms: 60000 }], records: [usage], total: 1, page: 1, page_size: 20 },
  '/api/v1/dashboard/app-usage/day': { day: '2026-10-14', start_ms: ms - 8 * 3600000, end_ms: ms + 16 * 3600000, total: 1, limit: 1000, truncated: false, records: [usage] },
  '/api/v1/dashboard/locations': { locations: [location], total: 1 },
  '/api/v1/dashboard/call-recordings': { records: [{ record_id: 'fixture', platform: 'wechat', call_type: 'voice', counterpart_display: long, call_started_at: ms, call_ended_at: ms + 60000, call_duration_ms: 60000, recording_started_at: ms, recording_ended_at: ms + 60000, audio_duration_ms: 60000, recording_status: 'ended', quality_status: 'unverified', stored_at: stamp }], total: 1 },
  '/api/v1/dashboard/call-recordings/call-log': { records: [{ source_id: 'fixture', number: '13800000000', name: long, type: 1, date: ms, duration_seconds: 60 }], total: 1, sync: { request_id: null, status: 'synced' } },
  '/api/v1/dashboard/relationships': { relationships: [{ conversation_id: 1, alias: long + pkg, display_name: long, platform: 'wechat', message_count: 1, relationship_type: 'friend', intimacy_level: 50, humor_level: 50, notes: long }], total: 1 },
  '/api/v1/dashboard/relationships/1/ai-profile': { profile: null },
  '/api/v1/dashboard/relationships/1/incoming-sticker-assets': { assets: [] },
  '/api/v1/dashboard/relationships/1/sticker-candidates': { candidates: [] },
};
const pages = [
 ['/', '#source-chart canvas'], ['/timeline', '#heatmap-chart canvas'], ['/report', '#report-source-chart canvas'], ['/apps', '#apps-chart canvas'], ['/phrases', '.phrase-item'], ['/user-phrases', '.phrase-item'], ['/wordcloud', '#wordcloud-chart canvas'], ['/completion', 'tbody tr'], ['/app-usage', '.timeline-lane'], ['/activity', 'tbody tr'], ['/clipboard', '#clipboard-chart canvas'], ['/clipboard-history', '.list > *'], ['/call-recordings', '.calls tbody tr'], ['/locations', '.leaflet-tile-loaded'], ['/relationships', '.profile-form'],
];
// 分页专用响应：不同统计值用于证明翻页不会覆盖上方统计。
const usagePages = [1, 2].map(page => ({
 ...structuredClone(data['/api/v1/dashboard/app-usage']), page, total: 51, page_size: 50,
 overview: { duration_ms: page * 60000, count: page, gap_count: 0, last_received_at: stamp },
 records: [{ ...usage, id: String(page), app_name: `分页测试第${page}页`, package_name: 'com.fixture.pagination' }],
}));
module.exports = { userId, data, pages, usagePages };
