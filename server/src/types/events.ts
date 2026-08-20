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
