export const CHAT_PLATFORMS = ['wechat', 'qq', 'douyin'] as const;
export const CHAT_DIRECTIONS = ['incoming', 'outgoing', 'system'] as const;
export const CHAT_MESSAGE_TYPES = [
  'text',
  'emoji',
  'image',
  'sticker',
  'video',
  'voice',
  'link',
  'file',
  'music',
  'location',
  'contact',
  'mini_app',
  'red_packet',
  'transfer',
  'system',
  'recalled',
  'unknown',
] as const;

export interface CapturedConversationInput {
  platform: (typeof CHAT_PLATFORMS)[number];
  account_key: string;
  external_key: string;
  display_name?: string | null;
  conversation_type: 'direct' | 'group' | 'unknown';
  identity_confidence: number;
}

export interface CapturedMessageInput {
  id: string;
  fingerprint: string;
  content_fingerprint: string;
  sender_key: string;
  sender_name?: string | null;
  direction: (typeof CHAT_DIRECTIONS)[number];
  message_type: (typeof CHAT_MESSAGE_TYPES)[number];
  text?: string | null;
  displayed_time?: string | null;
  occurred_at?: string | null;
  captured_at: string;
  sequence_hint?: number | null;
  asset_sha256?: string[];
  metadata?: Record<string, unknown>;
}
