import {
  CHAT_DIRECTIONS,
  CHAT_MESSAGE_TYPES,
  CHAT_PLATFORMS,
  type CapturedConversationInput,
  type CapturedMessageInput,
} from '../types/chat.js';

const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const CONVERSATION_TYPES = ['direct', 'group', 'unknown'] as const;

function requireRecord(input: unknown, name: string): Record<string, unknown> {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    throw new Error(`${name} must be an object`);
  }
  return input as Record<string, unknown>;
}

function requireNonEmptyString(value: unknown, name: string): asserts value is string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${name} must be a non-empty string`);
  }
}

function requireEnum<T extends string>(
  value: unknown,
  values: readonly T[],
  name: string,
): asserts value is T {
  if (typeof value !== 'string' || !values.includes(value as T)) {
    throw new Error(`${name} is invalid`);
  }
}

function requireSha256(value: unknown, name: string): asserts value is string {
  if (typeof value !== 'string' || !SHA256_PATTERN.test(value)) {
    throw new Error(`${name} must be a 64 character lowercase hexadecimal string`);
  }
}

export function validateCapturedConversation(input: unknown): CapturedConversationInput {
  const value = requireRecord(input, 'conversation');
  requireEnum(value.platform, CHAT_PLATFORMS, 'platform');
  requireNonEmptyString(value.account_key, 'account_key');
  requireNonEmptyString(value.external_key, 'external_key');
  requireEnum(value.conversation_type, CONVERSATION_TYPES, 'conversation_type');
  if (
    typeof value.identity_confidence !== 'number'
    || !Number.isFinite(value.identity_confidence)
    || value.identity_confidence < 0
    || value.identity_confidence > 1
  ) {
    throw new Error('identity_confidence must be between 0 and 1');
  }
  if (value.display_name !== undefined && value.display_name !== null && typeof value.display_name !== 'string') {
    throw new Error('display_name must be a string or null');
  }
  return value as unknown as CapturedConversationInput;
}

export function validateCapturedMessage(input: unknown): CapturedMessageInput {
  const value = requireRecord(input, 'message');
  requireNonEmptyString(value.id, 'id');
  requireSha256(value.fingerprint, 'fingerprint');
  requireSha256(value.content_fingerprint, 'content_fingerprint');
  requireNonEmptyString(value.sender_key, 'sender_key');
  requireEnum(value.direction, CHAT_DIRECTIONS, 'direction');
  requireEnum(value.message_type, CHAT_MESSAGE_TYPES, 'message_type');
  requireNonEmptyString(value.captured_at, 'captured_at');

  if (value.text !== undefined && value.text !== null) {
    if (typeof value.text !== 'string' || value.text.length > 20_000) {
      throw new Error('text must contain at most 20000 characters');
    }
  }
  if (value.asset_sha256 !== undefined) {
    if (!Array.isArray(value.asset_sha256)) {
      throw new Error('asset_sha256 must be an array');
    }
    value.asset_sha256.forEach((sha256) => requireSha256(sha256, 'asset_sha256'));
  }
  return value as unknown as CapturedMessageInput;
}
