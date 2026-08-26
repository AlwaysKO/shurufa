/** 与后端 /api/v1/dashboard/* 对应的数据类型 */
import { ref } from 'vue';

const USER_STORAGE_KEY = 'shurufa_dashboard_user_id';
export const currentUserId = ref(localStorage.getItem(USER_STORAGE_KEY) ?? '');

export function setCurrentUserId(userId: string): void {
  currentUserId.value = userId;
  if (userId) localStorage.setItem(USER_STORAGE_KEY, userId);
  else localStorage.removeItem(USER_STORAGE_KEY);
}

export function scopedAssetUrl(url: string): string {
  if (!url.startsWith('/uploads/') || !currentUserId.value) return url;
  const parsed = new URL(url, window.location.origin);
  parsed.searchParams.set('user_id', currentUserId.value);
  return `${parsed.pathname}${parsed.search}`;
}

function withDashboardUser(url: string): string {
  if (!url.startsWith('/api/v1/dashboard') || !currentUserId.value) return url;
  const parsed = new URL(url, window.location.origin);
  parsed.searchParams.set('user_id', currentUserId.value);
  return `${parsed.pathname}${parsed.search}${parsed.hash}`;
}

export interface OverviewData {
  days: number;
  today: { input_events: string; input_chars: string; clipboard_count: string; delete_count: string };
  period: { event_count: string; input_chars: string; active_days: string };
  total_chars: string;
  source_distribution: { typed: number; pasted: number; external: number; voice: number; total: number };
}

export interface TimelinePoint {
  day: string;
  event_count: string;
  input_chars: string;
}

export interface HourPoint {
  hour: number;
  event_count: string;
  input_chars: string;
}

export interface HeatmapCell {
  dow: number; // ISODOW: 1=周一 … 7=周日
  hour: number;
  chars: string;
}

export interface AppStat {
  package_name: string;
  event_count: string;
  input_chars: string;
}

export interface PhraseRow {
  phrase: string;
  use_count: string;
  use_days: string;
  last_used_at: string;
}

export interface PrefixContinuation {
  text: string;
  count: number;
  pct: number;
}

export interface PrefixRow {
  prefix: string;
  count: number;
  continuations: PrefixContinuation[];
}

export interface CompletionStats {
  show_count: number;
  accept_count: number;
  accept_rate: number;
  saved_chars: number;
  candidate_count: number;
  top: Array<{
    prefix: string;
    completion: string;
    package_name: string | null;
    use_count: string;
    show_count: string;
    accept_count: string;
    score: string;
    last_used_at: string | null;
  }>;
}

export interface ClipboardData {
  days: number;
  counts: { copy_count: string; paste_count: string };
  type_distribution: { url: number; email: number; phone: number; plain: number };
  paste_intervals: { within_10s: string; within_1m: string; within_10m: string };
  top: Array<{ text: string; count: string; last_used_at: string }>;
}

export interface DeviceRow {
  id: string;
  dashboard_name: string | null;
  tags: string | null;
  name: string | null;
  platform: string | null;
  model: string | null;
  os_version: string | null;
  app_version: string | null;
  brand: string | null;
  sdk_int: number | null;
  screen_resolution: string | null;
  locale: string | null;
  region: string | null;
  hardware: string | null;
  rom_version: string | null;
  ram_mb: number | null;
  last_seen_at: string;
}

export interface UserDirectoryPage {
  total: number;
  page: number;
  page_size: number;
  users: DeviceRow[];
}

export interface ActivityItem {
  id: string;
  occurred_at: string;
  event_type: string;
  content_type: 'text' | 'voice' | 'image' | string;
  text: string | null;
  input_code: string | null;
  package_name: string | null;
  device_id: string;
  session_id: string | null;
  client_ip: string | null;
  ip_location: string | null;
  network_type: string | null;
}

export interface ActivityQuery {
  device_id?: string;
  package_name?: string;
  from?: string;
  to?: string;
  days?: number;
  q?: string;
  type?: string;
  all?: boolean;
  page?: number;
  page_size?: number;
}

export interface ActivityPage {
  total: number;
  page: number;
  page_size: number;
  items: ActivityItem[];
}

export interface LocationRow {
  id: string;
  device_id: string;
  latitude: string;
  longitude: string;
  accuracy: string | null;
  provider: string | null;
  speed: string | null;
  address: string | null;
  occurred_at: string;
  first_seen_at: string;
  last_seen_at: string;
}

export interface ReportData {
  type: 'daily' | 'weekly';
  date: string;
  summary: {
    input_events: string;
    input_chars: string;
    copy_count: string;
    delete_count: string;
    voice_count: string;
    device_count: string;
    active_days: string;
  };
  peak_hours: Array<{ hour: number; chars: string }>;
  source_distribution: { typed: number; pasted: number; external: number; voice: number; total: number };
  top_apps: Array<{ package_name: string; event_count: string; input_chars: string }>;
  top_phrases: Array<{ phrase: string; use_count: string }>;
  top_locations: Array<{
    latitude: string;
    longitude: string;
    address: string | null;
    count: string;
    last_seen_at: string;
  }>;
}

export interface ExportData {
  exported_at: string;
  counts: {
    devices: number;
    sessions: number;
    events: number;
    phrases: number;
    completions: number;
    locations: number;
  };
  devices: unknown[];
  sessions: unknown[];
  events: unknown[];
  phrases: unknown[];
  completions: unknown[];
  locations: unknown[];
}

export interface CleanupResult {
  scope: 'events' | 'all';
  deleted: Record<string, number>;
}

export interface StickerRow {
  id: number;
  keywords: string;
  url: string;
  format: string;
  width: number | null;
  height: number | null;
  useCount: string;
  createdAt: string;
}

export interface StickerPage {
  total: number;
  stickers: StickerRow[];
}

export interface UserPhraseRow {
  id: number;
  content: string;
  sortOrder: number;
  useCount: string;
  createdAt: string;
}

export interface UserPhrasePage {
  total: number;
  phrases: UserPhraseRow[];
}

export interface ChatCaptureOverview {
  conversation_count: number;
  message_count: number;
  media_count: number;
}

export interface ChatConversationRow {
  id: number;
  platform: 'wechat' | 'qq' | 'douyin';
  account_key: string;
  external_key: string;
  display_name: string | null;
  conversation_type: 'direct' | 'group' | 'unknown';
  identity_confidence: number;
  message_count: number;
  first_seen_at: string;
  last_seen_at: string;
  last_message_at: string | null;
}

export interface ChatMessageAsset {
  id: number;
  sha256: string;
  mime_type: string;
  width: number | null;
  height: number | null;
  role: string;
  position: number;
  url: string;
}

export interface ChatMessageRow {
  id: string;
  platform: 'wechat' | 'qq' | 'douyin';
  direction: 'incoming' | 'outgoing' | 'system';
  message_type: string;
  sender_key: string;
  sender_name: string | null;
  text: string | null;
  displayed_time: string | null;
  occurred_at: string | null;
  captured_at: string;
  sequence_hint: number | null;
  metadata: Record<string, unknown>;
  assets: ChatMessageAsset[];
}

export type RelationshipType =
  | 'unknown'
  | 'friend'
  | 'family'
  | 'partner'
  | 'colleague'
  | 'customer'
  | 'group'
  | 'other';

export interface RelationshipRow {
  conversation_id: number;
  platform: 'wechat' | 'qq' | 'douyin';
  account_key: string;
  external_key: string;
  display_name: string | null;
  conversation_type: 'direct' | 'group' | 'unknown';
  relationship_type: RelationshipType;
  alias: string | null;
  intimacy_level: number;
  humor_level: number;
  notes: string | null;
  message_count: number;
  last_seen_at: string;
  last_message_at: string | null;
  updated_at: string | null;
}

export interface RelationshipProfileInput {
  relationship_type: RelationshipType;
  alias: string | null;
  intimacy_level: number;
  humor_level: number;
  notes: string | null;
}

export interface ZeroTokenCandidateRow {
  text: string;
  source: 'context_match' | 'conversation_frequency' | 'relationship_type_frequency' | 'global_frequency';
  use_count: number;
  last_used_at: string;
}

export interface ZeroTokenCandidateResponse {
  conversation_id: number | null;
  relationship_type: RelationshipType;
  candidates: ZeroTokenCandidateRow[];
  reason?: 'conversation_not_found';
}

export type StickerCandidateSource =
  | 'sticker_counterattack'
  | 'sticker_conversation_frequency'
  | 'sticker_relationship_type_frequency'
  | 'sticker_global_frequency';

export interface IncomingStickerAsset {
  asset_id: number;
  sha256: string;
  mime_type: string;
  width: number | null;
  height: number | null;
  url: string;
  last_seen_at: string;
}

export interface StickerAssetCandidate {
  asset_id: number;
  sha256: string;
  mime_type: string;
  width: number | null;
  height: number | null;
  url: string;
  source: StickerCandidateSource;
  use_count: number;
  last_used_at: string;
}

export interface StickerCandidateResponse {
  conversation_id: number | null;
  relationship_type: RelationshipType;
  incoming_asset_sha256: string | null;
  candidates: StickerAssetCandidate[];
  reason?: 'conversation_not_found';
}

/** 删除文件：fetch 删除 JSON 外的二进制响应 */
async function del(url: string): Promise<void> {
  url = withDashboardUser(url);
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
}

async function patch<T>(url: string, body: unknown): Promise<T> {
  url = withDashboardUser(url);
  const res = await fetch(url, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

async function get<T>(url: string): Promise<T> {
  url = withDashboardUser(url);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

async function post<T>(url: string, body: unknown): Promise<T> {
  url = withDashboardUser(url);
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

async function put<T>(url: string, body: unknown): Promise<T> {
  url = withDashboardUser(url);
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

export const api = {
  overview: (days = 7) => get<OverviewData>(`/api/v1/dashboard/overview?days=${days}`),
  timeline: (days = 30) => get<{ days: number; timeline: TimelinePoint[] }>(`/api/v1/dashboard/timeline?days=${days}`),
  hours: (days = 30) => get<{ days: number; hours: HourPoint[] }>(`/api/v1/dashboard/hours?days=${days}`),
  heatmap: (days = 30) => get<{ days: number; cells: HeatmapCell[] }>(`/api/v1/dashboard/heatmap?days=${days}`),
  apps: (days = 30) => get<{ days: number; apps: AppStat[] }>(`/api/v1/dashboard/apps?days=${days}`),
  phrases: (kind: 'word' | 'phrase', days: number | 'all' = 'all', limit = 50) =>
    get<{ kind: string; phrases: PhraseRow[] }>(`/api/v1/dashboard/phrases?kind=${kind}&days=${days}&limit=${limit}`),
  prefixes: () => get<{ prefixes: PrefixRow[] }>(`/api/v1/dashboard/prefixes`),
  completions: () => get<CompletionStats>(`/api/v1/dashboard/completions`),
  clipboard: (days = 30) => get<ClipboardData>(`/api/v1/dashboard/clipboard?days=${days}`),
  users: (query: { q?: string; id?: string; page?: number; page_size?: number } = {}) => {
    const p = new URLSearchParams();
    if (query.q) p.set('q', query.q);
    if (query.id) p.set('id', query.id);
    if (query.page) p.set('page', String(query.page));
    if (query.page_size) p.set('page_size', String(query.page_size));
    return get<UserDirectoryPage>(`/api/v1/dashboard/users?${p.toString()}`);
  },
  updateUser: (id: string, body: { dashboard_name: string; tags: string }) =>
    patch<{ user: DeviceRow }>(`/api/v1/dashboard/users/${id}`, body),
  devices: () => get<{ devices: DeviceRow[] }>(`/api/v1/dashboard/devices`),
  events: (query: ActivityQuery = {}) => {
    const p = new URLSearchParams();
    if (query.device_id) p.set('device_id', query.device_id);
    if (query.package_name) p.set('package_name', query.package_name);
    if (query.from) p.set('from', query.from);
    if (query.to) p.set('to', query.to);
    if (query.days) p.set('days', String(query.days));
    if (query.q) p.set('q', query.q);
    if (query.type && query.type !== 'all') p.set('type', query.type);
    if (query.all) p.set('all', '1');
    if (query.page) p.set('page', String(query.page));
    if (query.page_size) p.set('page_size', String(query.page_size));
    return get<ActivityPage>(`/api/v1/dashboard/events?${p.toString()}`);
  },
  locations: (query: { device_id?: string; days?: number; limit?: number } = {}) => {
    const p = new URLSearchParams();
    if (query.device_id) p.set('device_id', query.device_id);
    if (query.days) p.set('days', String(query.days));
    if (query.limit) p.set('limit', String(query.limit));
    return get<{ days: number; total: number; locations: LocationRow[] }>(`/api/v1/dashboard/locations?${p.toString()}`);
  },
  report: (type: 'daily' | 'weekly', date: string) => get<ReportData>(`/api/v1/dashboard/report?type=${type}&date=${date}`),
  exportData: () => get<ExportData>(`/api/v1/dashboard/export`),
  cleanup: (body: { confirm: string; scope: 'events' | 'all'; from?: string; to?: string; package_name?: string }) =>
    post<CleanupResult>(`/api/v1/dashboard/cleanup`, body),
  stickers: (q = '') => {
    const p = new URLSearchParams();
    if (q) p.set('q', q);
    return get<StickerPage>(`/api/v1/dashboard/stickers?${p.toString()}`);
  },
  uploadSticker: (body: { file_base64: string; filename: string; keywords: string; width?: number; height?: number }) =>
    post<StickerRow>(`/api/v1/dashboard/stickers`, body),
  updateStickerKeywords: (id: number, keywords: string) =>
    patch<{ ok: boolean }>(`/api/v1/dashboard/stickers/${id}`, { keywords }),
  deleteSticker: (id: number) => del(`/api/v1/dashboard/stickers/${id}`),
  userPhrases: (q = '') => {
    const p = new URLSearchParams();
    if (q) p.set('q', q);
    return get<UserPhrasePage>(`/api/v1/dashboard/user-phrases?${p.toString()}`);
  },
  addUserPhrase: (content: string) => post<UserPhraseRow>(`/api/v1/dashboard/user-phrases`, { content }),
  updateUserPhrase: (id: number, content: string) =>
    patch<{ ok: boolean }>(`/api/v1/dashboard/user-phrases/${id}`, { content }),
  deleteUserPhrase: (id: number) => del(`/api/v1/dashboard/user-phrases/${id}`),
  chatCaptureOverview: () => get<ChatCaptureOverview>('/api/v1/dashboard/chat/overview'),
  chatConversations: (page = 1, pageSize = 100) =>
    get<{ total: number; page: number; page_size: number; conversations: ChatConversationRow[] }>(
      `/api/v1/dashboard/chat/conversations?page=${page}&page_size=${pageSize}`,
    ),
  chatMessages: (conversationId: number, page = 1, pageSize = 100) =>
    get<{ total: number; page: number; page_size: number; messages: ChatMessageRow[] }>(
      `/api/v1/dashboard/chat/messages?conversation_id=${conversationId}&page=${page}&page_size=${pageSize}`,
    ),
  relationships: (page = 1, pageSize = 100) =>
    get<{ total: number; page: number; page_size: number; relationships: RelationshipRow[] }>(
      `/api/v1/dashboard/relationships?page=${page}&page_size=${pageSize}`,
    ),
  updateRelationship: (conversationId: number, body: RelationshipProfileInput) =>
    put<{ ok: boolean; profile: RelationshipProfileInput & { conversation_id: number } }>(
      `/api/v1/dashboard/relationships/${conversationId}`,
      body,
    ),
  relationshipCandidates: (conversationId: number, contextText = '', limit = 6) => {
    const query = new URLSearchParams({ context_text: contextText, limit: String(limit) });
    return get<ZeroTokenCandidateResponse>(
      `/api/v1/dashboard/relationships/${conversationId}/candidates?${query.toString()}`,
    );
  },
  relationshipIncomingStickerAssets: (conversationId: number, limit = 6) =>
    get<{ assets: IncomingStickerAsset[] }>(
      `/api/v1/dashboard/relationships/${conversationId}/incoming-sticker-assets?limit=${limit}`,
    ),
  relationshipStickerCandidates: (
    conversationId: number,
    incomingAssetSha256: string | null,
    limit = 6,
  ) => {
    const query = new URLSearchParams({ limit: String(limit) });
    if (incomingAssetSha256) query.set('incoming_asset_sha256', incomingAssetSha256);
    return get<StickerCandidateResponse>(
      `/api/v1/dashboard/relationships/${conversationId}/sticker-candidates?${query.toString()}`,
    );
  },
};

/** 事件类型 → 中文标签 */
export const eventTypeName = (t: string): string =>
  ({
    commit: '输入',
    candidate_commit: '候选上屏',
    external_insert: '外部插入',
    paste: '粘贴',
    paste_inferred: '推测粘贴',
    clipboard_change: '复制',
    voice: '语音',
    delete: '删除',
    external_delete: '外部删除',
    key: '按键',
    compose: '组字',
    completion_show: '补全展示',
    completion_accept: '补全接受',
  })[t] ?? t;

/** 设备显示名：品牌 + 型号（+ 自定义名） */
export const deviceLabel = (d?: DeviceRow | null): string => {
  if (!d) return '-';
  if (d.dashboard_name?.trim()) return d.dashboard_name.trim();
  const parts = [d.brand, d.model, d.name].filter(Boolean);
  return parts.join(' ') || d.id.slice(0, 8);
};

/** 设备完整档案（tooltip 多行展示） */
export const deviceDetailLines = (d?: DeviceRow | null): string[] => {
  if (!d) return [];
  const lines: string[] = [];
  if (d.brand || d.model) lines.push(`设备：${[d.brand, d.model].filter(Boolean).join(' ')}`);
  if (d.name) lines.push(`名称：${d.name}`);
  if (d.os_version || d.sdk_int != null) lines.push(`系统：Android ${d.os_version ?? '?'}（API ${d.sdk_int ?? '?'}）`);
  if (d.rom_version) lines.push(`ROM：${d.rom_version}`);
  if (d.hardware) lines.push(`硬件平台：${d.hardware}`);
  if (d.screen_resolution) lines.push(`分辨率：${d.screen_resolution}`);
  if (d.ram_mb) lines.push(`内存：${(d.ram_mb / 1024).toFixed(0)} GB`);
  if (d.locale || d.region) lines.push(`区域：${[d.locale, d.region].filter(Boolean).join(' / ')}`);
  if (d.app_version) lines.push(`输入法版本：v${d.app_version}`);
  return lines;
};

/** 网络类型 → 中文标签 */
export const networkName = (t: string | null): string =>
  ({ wifi: 'Wi-Fi', mobile: '移动网络', ethernet: '有线', bluetooth: '蓝牙', vpn: 'VPN' })[t ?? ''] ?? t ?? '-';

/** 常见包名 → 中文名 */
export const appName = (pkg: string | null): string => {
  if (!pkg) return '未知';
  const map: Record<string, string> = {
    'com.tencent.mm': '微信',
    'com.tencent.mobileqq': 'QQ',
    'com.android.chrome': 'Chrome',
    'com.android.browser': '浏览器',
    'com.google.android.gm': 'Gmail',
    'com.tencent.wework': '企业微信',
    'com.alibaba.android.rimet': '钉钉',
    'com.zhihu.android': '知乎',
    'com.tencent.wechat': '微信',
  };
  const short = pkg.split('.').slice(-2).join('.');
  return map[pkg] ?? short;
};
