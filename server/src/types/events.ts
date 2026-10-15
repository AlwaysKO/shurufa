/** 输入事件类型（与 Android 端 Kotlin 枚举保持一致） */
export const EVENT_TYPES = [
  'key',
  'compose',
  'commit',
  'candidate_commit',
  'delete',
  'clipboard_change',
  'paste',
  'paste_inferred',
  'external_insert',
  'external_delete',
  'completion_show',
  'completion_accept',
  'voice',
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

/** 移动端批量上报的单条事件 */
export interface MobileEvent {
  /** 客户端生成的 UUID，服务端以此做幂等 */
  id: string;
  event_type: EventType;
  occurred_at: string;
  device_id: string;
  session_id?: string | null;
  sequence_no?: number | null;
  package_name?: string | null;
  /** 手机系统显示名，可选；存入事件 metadata，旧客户端仍兼容。 */
  app_name?: string | null;
  editor_id?: string | null;
  text?: string | null;
  text_before?: string | null;
  text_after?: string | null;
  input_code?: string | null;
  clipboard_id?: string | null;
  source?: string | null;
  source_confidence?: number | null;
  network_type?: string | null;
  metadata?: Record<string, unknown>;
}

export interface DeviceInfo {
  last_interaction_at?: string | null;
  last_interaction_source?: string | null;
  id: string;
  name?: string;
  platform?: string;
  model?: string;
  os_version?: string;
  app_version?: string;
  /** 设备详细档案（Android Build / 系统信息） */
  brand?: string;
  sdk_int?: number;
  screen_resolution?: string;
  locale?: string;
  region?: string;
  hardware?: string;
  rom_version?: string;
  ram_mb?: number;
}

export interface SessionInfo {
  id: string;
  device_id: string;
  started_at: string;
  ended_at?: string | null;
  package_name?: string | null;
  editor_id?: string | null;
  event_count?: number;
}

/** 原始系统使用事件快照；version只在证据变化时生成，不能使用上传时钟。 */
export interface DeviceUsageEvidence {
  version: string;
  device_id: string;
  app_activity?: { at_ms: number; source: 'usage_resume' | 'usage_pause' };
  screen_off?: { at_ms: number; source: 'usage_screen_off' };
  lock?: { at_ms: number; source: 'usage_keyguard_shown' };
}
