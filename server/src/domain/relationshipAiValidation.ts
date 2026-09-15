import { validateRelationshipCandidateQuery } from './relationshipValidation.js';
import type { AiReplyQuery, RelationshipAiProfileDocument } from '../types/relationshipAi.js';

function record(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${name} is invalid`);
  return value as Record<string, unknown>;
}

function boundedString(value: unknown, name: string, max: number): string {
  if (typeof value !== 'string') throw new Error(`${name} is invalid`);
  const normalized = value.trim();
  if (!normalized || normalized.length > max) throw new Error(`${name} is invalid`);
  return normalized;
}

function stringList(value: unknown, name: string, maxItems: number, maxLength: number): string[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > maxItems) throw new Error(`${name} is invalid`);
  const result: string[] = [];
  const seen = new Set<string>();
  for (const item of value) {
    const normalized = boundedString(item, name, maxLength);
    if (!seen.has(normalized)) { seen.add(normalized); result.push(normalized); }
  }
  return result;
}

export function validateAiProfileDocument(input: unknown): RelationshipAiProfileDocument {
  const value = record(input, 'profile');
  return {
    summary: boundedString(value.summary, 'summary', 1_000),
    user_style: stringList(value.user_style, 'user_style', 20, 200),
    peer_style: stringList(value.peer_style, 'peer_style', 20, 200),
    relationship_signals: stringList(value.relationship_signals, 'relationship_signals', 20, 200),
    preferred_tone: stringList(value.preferred_tone, 'preferred_tone', 20, 200),
    avoid: stringList(value.avoid, 'avoid', 20, 200),
    evidence_message_ids: stringList(value.evidence_message_ids, 'evidence_message_ids', 100, 100),
  };
}

export function validateAiReplyQuery(input: unknown): AiReplyQuery {
  const value = record(input, 'query');
  const common = validateRelationshipCandidateQuery(value);
  const refreshCount = value.refresh_count ?? 0;
  if (!Number.isInteger(refreshCount) || Number(refreshCount) < 0 || Number(refreshCount) > 20) {
    throw new Error('refresh_count is invalid');
  }
  const rawSessionId = value.reply_session_id;
  if (rawSessionId !== undefined && (typeof rawSessionId !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(rawSessionId))) {
    throw new Error('reply_session_id is invalid');
  }
  return {
    ...common,
    refreshCount: Number(refreshCount),
    sessionId: (rawSessionId as string | undefined) ?? null,
  };
}

export function validateAiReplyResult(input: unknown, limit: number): string[] {
  const value = record(input, 'AI result');
  const replies = stringList(value.replies, 'replies', 20, 500);
  if (replies.length === 0) throw new Error('replies is invalid');
  return replies.slice(0, limit);
}
