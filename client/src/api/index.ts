import type { DeliveryRule, DeliveryState } from './expressionDelivery';
/** 与后端 /api/v1/dashboard/* 对应的数据类型 */
import { ref } from 'vue';
import { dashboardFetch, dashboardUpload } from '../auth';

const USER_STORAGE_KEY = 'shurufa_dashboard_user_id';
export const currentUserId = ref(localStorage.getItem(USER_STORAGE_KEY) ?? '');

export function setCurrentUserId(userId: string): void {
  currentUserId.value = userId;
  if (userId) localStorage.setItem(USER_STORAGE_KEY, userId);
  else localStorage.removeItem(USER_STORAGE_KEY);
}

export function scopedAssetUrl(url: string): string {
  if (!url.startsWith('/uploads/')) return url;
  const userId = currentUserId.value || (/^\/uploads\/(stickers|expression)\//.test(url) ? '00000000-0000-4000-8000-000000000000' : '');
  if (!userId) return url;
  const parsed = new URL(url, window.location.origin);
  parsed.searchParams.set('user_id', userId);
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
  app_name?: string | null;
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
  save_uploads?: boolean;
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
  text_before?: string | null;
  text_after?: string | null;
  edit_count?: number;
  edit_complete?: boolean;
  edit_events?: ActivityItem[];
  editor_id?: string | null;
  sequence_no?: number | string | null;
  input_code: string | null;
  package_name: string | null;
  device_id: string;
  session_id: string | null;
  client_ip: string | null;
  ip_location: string | null;
  network_type: string | null;
}

export interface ActivityDeleteRequest {
  confirm: 'DELETE';
  mode: 'single' | 'group';
  event_ids: string[];
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
  grouped?: boolean;
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
  address_status?: 'pending' | 'resolving' | 'failed' | 'resolved';
  address_error?: string | null;
  address_retry_at?: string | null;
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
  top_apps: Array<{ package_name: string; app_name?: string | null; event_count: string; input_chars: string }>;
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

export interface CollectorSetting {
  collector_base_url: string;
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

export interface SynthesisSafeArea { x: number; y: number; width: number; height: number }
export interface SynthesisLayout {
  minFontSize: number; maxFontSize: number; textColor: string; strokeColor: string;
  strokeWidth: number; alignment: 'center'; maxLines: number;
}
export interface SynthesisAsset {
  id: string; name: string; source: 'system' | 'personal'; deletable: boolean;
  sourceStatement?: string;
  url: string; format: 'gif'; sha256: string; width: number; height: number;
  textSafeArea: SynthesisSafeArea; layout: SynthesisLayout;
}
export interface SynthesisUpload {
  file_base64: string; filename: string; name: string; noTextConfirmed?: boolean;
  rightsConfirmed?: boolean; sourceStatement?: string; textSafeArea: SynthesisSafeArea; layout: SynthesisLayout;
}

export interface LibrarySticker {
  sha256?: string;
  id: string | number;
  source: 'system' | 'personal';
  keywords: string[];
  url: string;
  format: string;
  width: number | null;
  height: number | null;
  useCount: number | null;
}
export interface StickerKeywordGroup {
  keyword: string;
  aliases: string[];
  confirmedAliases: string[];
  category: string;
  planned: boolean;
  custom: boolean;
  assets: LibrarySticker[];
}
export interface StickerLibrary {
  groups: StickerKeywordGroup[];
  systemCount: number;
  personalCount: number;
  warnings: string[];
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
  is_pending_group?: boolean;
  is_pending_source?: boolean;
  is_name_group?: boolean;
  group_name?: string | null;
  source_ids?: number[];
  source_count?: number;
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

export interface ChatImageTarget { message_id: string; asset_id: number }
export interface ChatPreviewImage extends ChatImageTarget {
  url: string; alt: string; captured_at: string; ordinal: number; total: number;
}
export interface ChatImageDeleteRequest {
  confirm: 'DELETE'; conversation_id: number; images: ChatImageTarget[]; platform?: ChatConversationRow['platform']; group_name?: string;
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
  pending_diagnostic?: {
    reason: 'title_unreadable' | 'title_unconfirmed' | 'possible_existing_conversation' | 'non_chat_page';
    observed_title: string | null;
    identity_status: string | null;
    identity_source: string | null;
    non_chat_evidence?: 'metadata' | 'title_only';
    suggested_conversations: Array<{ id: number; display_name: string }>;
  };
  conversation_id?: number;
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

export interface RelationshipAiProfileDocument {
  summary: string;
  user_style: string[];
  peer_style: string[];
  relationship_signals: string[];
  preferred_tone: string[];
  avoid: string[];
  evidence_message_ids: string[];
}

export interface RelationshipAiProfile {
  version: number;
  profile: RelationshipAiProfileDocument;
  source_message_count: number;
  source_last_message_at: string | null;
  model: string;
  created_at: string;
}

export interface RelationshipReplyResponse {
  conversation_id: number | null;
  candidates: Array<{ text: string; source: 'zero_token' | 'ai' }>;
  ai_used: boolean;
  profile_version: number | null;
  fallback_reason?: string;
}

/** 删除文件：fetch 删除 JSON 外的二进制响应 */
async function del(url: string): Promise<void> {
  url = withDashboardUser(url);
  const res = await dashboardFetch(url, { method: 'DELETE' });
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
}

async function patch<T>(url: string, body: unknown): Promise<T> {
  url = withDashboardUser(url);
  const res = await dashboardFetch(url, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => null);
    throw new Error(typeof detail?.error === 'string' && detail.error.trim() ? detail.error : `API ${url} failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}

async function get<T>(url: string): Promise<T> {
  url = withDashboardUser(url);
  const res = await dashboardFetch(url);
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

async function post<T>(url: string, body: unknown): Promise<T> {
  url = withDashboardUser(url);
  const res = await dashboardFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

async function put<T>(url: string, body: unknown): Promise<T> {
  url = withDashboardUser(url);
  const res = await dashboardFetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`API ${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

/** 用户目录操作固定使用行ID，不依赖当前页面选中的另一台手机。 */
async function deviceControl<T>(id: string, action: 'saving' | 'delete', body: unknown): Promise<T> {
  const target = encodeURIComponent(id);
  const response = await dashboardFetch(`/api/v1/dashboard/users/${target}/${action}?user_id=${target}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  if (!response.ok) {
    const result = await response.json().catch(() => null);
    throw new Error(typeof result?.error === 'string' ? result.error : `操作失败（${response.status}）`);
  }
  return response.json();
}

export interface ActivityBatchDeleteRequest {
  confirm: 'DELETE';
  mode: 'single' | 'group';
  records: Array<{ id: string; event_ids: string[] }>;
}

// 保留发送配置的校验/版本冲突恢复说明，不改变其他接口错误约定。
async function deliveryWrite(method: 'PUT' | 'POST', suffix: string, body: unknown): Promise<DeliveryState> {
  const res = await dashboardFetch(withDashboardUser(`/api/v1/dashboard/settings/expression-delivery${suffix}`), {
    method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(typeof data?.message === 'string' ? data.message : `发送配置请求失败：${res.status}`);
  return data as DeliveryState;
}

export const api = {
  expressionDelivery: () => get<DeliveryState>('/api/v1/dashboard/settings/expression-delivery'),
  saveExpressionDelivery: (expectedRevision: number, rules: DeliveryRule[]) => deliveryWrite('PUT', '', { expectedRevision, rules }),
  rollbackExpressionDelivery: (expectedRevision: number, revision: number) => deliveryWrite('POST', '/rollback', { expectedRevision, revision }),
  deleteUser: (id: string) => deviceControl<{ deleted_device_id: string; files_pending: boolean }>(id, 'delete', { confirm: 'DELETE' }),
  setUserSaving: (id: string, save_uploads: boolean) => deviceControl<{ id: string; save_uploads: boolean }>(id, 'saving', { save_uploads }),
  deleteActivities: async (body: ActivityBatchDeleteRequest): Promise<{ deleted: number }> => {
    const url = withDashboardUser('/api/v1/dashboard/events/delete-batch');
    const response = await dashboardFetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => null) as { error?: string } | null;
      throw new Error(detail?.error || `批量删除失败（${response.status}）`);
    }
    return response.json();
  },
  deleteActivity: async (id: string, body: ActivityDeleteRequest): Promise<{ deleted: number }> => {
    const url = withDashboardUser(`/api/v1/dashboard/events/${encodeURIComponent(id)}/delete`);
    const response = await dashboardFetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (!response.ok) {
      const result = await response.json().catch(() => null);
      throw new Error(typeof result?.error === 'string' ? result.error : `删除请求失败（${response.status}）`);
    }
    return response.json();
  },
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
    if (query.grouped) p.set('grouped', '1');
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
  collectorSetting: () => get<CollectorSetting>('/api/v1/dashboard/settings/collector'),
  updateCollectorSetting: (collectorBaseUrl: string) =>
    put<{ ok: boolean; collector_base_url: string }>('/api/v1/dashboard/settings/collector', { collector_base_url: collectorBaseUrl }),
  synthesisLibrary: () => get<{ assets: SynthesisAsset[]; total: number }>('/api/v1/dashboard/synthesis-library'),
  uploadSynthesisAsset: async (body: SynthesisUpload): Promise<{ asset: SynthesisAsset; duplicate: boolean }> => {
    const url = withDashboardUser('/api/v1/dashboard/synthesis-library');
    const res = await dashboardFetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!res.ok) {
      const detail = await res.json().catch(() => null);
      throw new Error(typeof detail?.error === 'string' && detail.error.trim() ? detail.error : `API ${url} failed: ${res.status}`);
    }
    return res.json();
  },
  updateSynthesisAsset: (id: string, body: Omit<SynthesisUpload, 'file_base64' | 'filename'> & Partial<Pick<SynthesisUpload, 'file_base64' | 'filename'>>) => patch<{ asset: SynthesisAsset; files_pending?: boolean }>(`/api/v1/dashboard/synthesis-library/${encodeURIComponent(id)}`, body),
  deleteSynthesisAsset: async (id: string): Promise<{ ok: boolean; files_pending?: boolean }> => {
    const url = withDashboardUser(`/api/v1/dashboard/synthesis-library/${encodeURIComponent(id)}`);
    const res = await dashboardFetch(url, { method: 'DELETE' });
    if (!res.ok) {
      const detail = await res.json().catch(() => null);
      throw new Error(detail?.error || `API ${url} failed: ${res.status}`);
    }
    return res.json();
  },
  stickerLibrary: () => get<StickerLibrary>('/api/v1/dashboard/sticker-library'),
  deleteStickerGroup: async (keyword: string, body: {confirm: 'DELETE'; aliases: string[]; assetKeys: string[]}) => {
    const url = withDashboardUser(`/api/v1/dashboard/sticker-groups/${encodeURIComponent(keyword)}/delete`);
    const response = await dashboardFetch(url, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    if (!response.ok) {
      const detail = await response.json().catch(() => null);
      throw new Error(typeof detail?.error === 'string' ? detail.error : `删除失败（${response.status}）`);
    }
    return response.json() as Promise<{keyword: string; files_pending: boolean}>;
  },
  updateStickerGroup: (keyword: string, payload: { aliases?: string[]; assetOrder?: string[] }) => patch<{ group: StickerKeywordGroup }>(`/api/v1/dashboard/sticker-groups/${encodeURIComponent(keyword)}`, payload),
  addStickerKeyword: (keyword: string) => post<{ keyword: string; group?: StickerKeywordGroup }>('/api/v1/dashboard/sticker-keywords', { keyword }),
  stickers: (q = '') => {
    const p = new URLSearchParams();
    if (q) p.set('q', q);
    return get<StickerPage>(`/api/v1/dashboard/stickers?${p.toString()}`);
  },
  uploadSticker: (body: { file_base64: string; filename: string; keywords: string; group_keyword?: string; width?: number; height?: number }) =>
    post<StickerRow>(`/api/v1/dashboard/stickers`, body),
  uploadStickerFile: async (file: File, keyword: string, onProgress: (percent: number) => void) => {
    const query = new URLSearchParams({ filename: file.name, group_keyword: keyword });
    const response = await dashboardUpload(withDashboardUser(`/api/v1/dashboard/stickers?${query}`), file, onProgress);
    if (!response.ok) {
      const detail = await response.json().catch(() => null);
      throw new Error(typeof detail?.error === 'string' ? detail.error : `上传失败（${response.status}）`);
    }
    return response.json() as Promise<StickerRow & { group?: StickerKeywordGroup }>;
  },
  updateStickerKeywords: (id: number, keywords: string) =>
    patch<{ ok: boolean }>(`/api/v1/dashboard/stickers/${id}`, { keywords }),
  deleteSystemSticker: (id: string) => del(`/api/v1/dashboard/system-stickers/${encodeURIComponent(id)}`),
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
  chatCaptureOverview: (platform?: ChatConversationRow['platform']) => get<ChatCaptureOverview>(`/api/v1/dashboard/chat/overview${platform ? `?platform=${platform}` : ''}`),
  chatConversations: (page = 1, pageSize = 100, platform?: ChatConversationRow['platform'], query = '', groupPending = false, groupNames = false) =>
    get<{ total: number; page: number; page_size: number; conversations: ChatConversationRow[] }>(
      `/api/v1/dashboard/chat/conversations?page=${page}&page_size=${pageSize}${platform ? `&platform=${platform}` : ''}${query ? `&q=${encodeURIComponent(query)}` : ''}${groupPending ? '&group_pending=true' : ''}${groupNames ? '&group_names=true' : ''}`,
    ),
  chatConversationGroup: async (platform: ChatConversationRow['platform'], name: string) => {
    const result = await get<{ conversations: ChatConversationRow[] }>(`/api/v1/dashboard/chat/conversations?group_pending=true&group_names=true&platform=${platform}&name=${encodeURIComponent(name)}&page_size=1`);
    return { conversation: result.conversations[0] ?? null };
  },
  deleteChatConversationGroup: async (body: { confirm: 'DELETE'; platform: ChatConversationRow['platform']; group_name: string; source_ids: number[] }) => {
    const response = await dashboardFetch(withDashboardUser('/api/v1/dashboard/chat/conversation-groups/delete'), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => null);
      throw new Error(typeof detail?.error === 'string' ? detail.error : `会话组删除失败（${response.status}）`);
    }
    return response.json() as Promise<{ deleted_sources: number; files_pending: boolean }>;
  },
  resolveChatConversation: async (id: number): Promise<{ conversation: ChatConversationRow | null }> => {
    const response = await dashboardFetch(withDashboardUser(`/api/v1/dashboard/chat/conversations/${id}/resolve`));
    if (response.status === 404) return { conversation: null };
    if (!response.ok) throw new Error(`会话恢复失败（${response.status}）`);
    return response.json();
  },
  mergeChatConversation: async (source: number, target: number): Promise<{ target_id: number; moved_messages: number }> => {
    const response = await dashboardFetch(withDashboardUser(`/api/v1/dashboard/chat/conversations/${source}/merge`), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ confirm: 'MERGE', target_id: target }),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw new Error(body?.error || `合并失败（${response.status}）`);
    }
    return response.json();
  },
  chatMessages: (conversationId: number, page = 1, pageSize = 100, platform?: ChatConversationRow['platform'], groupName?: string) =>
    get<{ total: number; page: number; page_size: number; messages: ChatMessageRow[] }>(
      `/api/v1/dashboard/chat/messages?gallery=true&conversation_id=${conversationId}&page=${page}&page_size=${pageSize}${platform ? `&platform=${platform}` : ''}${groupName !== undefined ? `&group_name=${encodeURIComponent(groupName)}` : ''}`,
    ),
  chatAdjacentImage: (conversationId: number, messageId: string, assetId: number, direction: 'next' | 'previous', platform?: ChatConversationRow['platform'], groupName?: string) =>
    get<{ image: ChatPreviewImage | null }>(`/api/v1/dashboard/chat/images/adjacent?conversation_id=${conversationId}&message_id=${encodeURIComponent(messageId)}&asset_id=${assetId}&direction=${direction}${platform ? `&platform=${platform}` : ''}${groupName !== undefined ? `&group_name=${encodeURIComponent(groupName)}` : ''}`),
  deleteChatImages: async (body: ChatImageDeleteRequest): Promise<{ deleted_images: number; deleted_messages: number; files_pending: boolean }> => {
    const response = await dashboardFetch(withDashboardUser('/api/v1/dashboard/chat/images/delete-batch'), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => null);
      throw new Error(typeof detail?.error === 'string' ? detail.error : `图片删除失败（${response.status}）`);
    }
    return response.json();
  },
  deleteChatConversation: (conversationId: number) =>
    del(`/api/v1/dashboard/chat/conversations/${conversationId}`),
  deleteChatConversations: async (body: { confirm: 'DELETE'; platform: ChatConversationRow['platform']; conversations: Array<{ id: number } | { group_name: string; source_ids: number[] }> }) => {
    const response = await dashboardFetch(withDashboardUser('/api/v1/dashboard/chat/conversations/delete-batch'), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => null);
      throw new Error(typeof detail?.error === 'string' ? detail.error : `会话批量删除失败（${response.status}）`);
    }
    return response.json() as Promise<{ deleted_conversations: number; deleted_sources: number; deleted_messages: number; files_pending: boolean }>;
  },
  deleteChatImage: (messageId: string, assetId: number) =>
    del(`/api/v1/dashboard/chat/messages/${encodeURIComponent(messageId)}/assets/${assetId}`),
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
  relationshipAiProfile: (conversationId: number) =>
    get<{ profile: RelationshipAiProfile | null }>(
      `/api/v1/dashboard/relationships/${conversationId}/ai-profile`,
    ),
  trainRelationshipAiProfile: (conversationId: number) =>
    post<{ ok: boolean; profile: RelationshipAiProfile }>(
      `/api/v1/dashboard/relationships/${conversationId}/ai-profile/train`,
      {},
    ),
  relationshipAiReplies: (conversationId: number, contextText: string, refreshCount = 2, limit = 6) =>
    post<RelationshipReplyResponse>(
      `/api/v1/dashboard/relationships/${conversationId}/ai-replies`,
      { context_text: contextText, refresh_count: refreshCount, limit },
    ),
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

/** 优先系统真实名称，其次常见名称映射；未知应用保留完整包名。 */
export const appName = (pkg: string | null, reportedName?: string | null): string => {
  if (reportedName?.trim()) return reportedName.trim();
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
  return map[pkg] ?? pkg;
};
