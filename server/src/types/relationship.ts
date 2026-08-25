import type { CHAT_PLATFORMS } from './chat.js';

export const RELATIONSHIP_TYPES = [
  'unknown',
  'friend',
  'family',
  'partner',
  'colleague',
  'customer',
  'group',
  'other',
] as const;

export type RelationshipType = typeof RELATIONSHIP_TYPES[number];

export interface RelationshipProfileInput {
  relationship_type: RelationshipType;
  alias?: string | null;
  intimacy_level: number;
  humor_level: number;
  notes?: string | null;
}

export interface RelationshipConversationIdentity {
  platform: typeof CHAT_PLATFORMS[number];
  account_key: string;
  external_key: string;
}

export const ZERO_TOKEN_CANDIDATE_SOURCES = [
  'context_match',
  'conversation_frequency',
  'relationship_type_frequency',
  'global_frequency',
] as const;

export type ZeroTokenCandidateSource = typeof ZERO_TOKEN_CANDIDATE_SOURCES[number];

export interface ZeroTokenCandidate {
  text: string;
  source: ZeroTokenCandidateSource;
  use_count: number;
  last_used_at: string;
}

export interface RelationshipCandidateQuery {
  identity: RelationshipConversationIdentity;
  contextText: string;
  limit: number;
}

export interface ZeroTokenCandidateResult {
  conversation_id: number | null;
  relationship_type: RelationshipType;
  candidates: ZeroTokenCandidate[];
  reason?: 'conversation_not_found';
}

export const STICKER_CANDIDATE_SOURCES = [
  'sticker_counterattack',
  'sticker_conversation_frequency',
  'sticker_relationship_type_frequency',
  'sticker_global_frequency',
] as const;

export type StickerCandidateSource = typeof STICKER_CANDIDATE_SOURCES[number];

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

export interface IncomingStickerAsset {
  asset_id: number;
  sha256: string;
  mime_type: string;
  width: number | null;
  height: number | null;
  url: string;
  last_seen_at: string;
}

export interface StickerCandidateQuery {
  identity: RelationshipConversationIdentity;
  incomingAssetSha256: string | null;
  limit: number;
}

export interface StickerCandidateResult {
  conversation_id: number | null;
  relationship_type: RelationshipType;
  incoming_asset_sha256: string | null;
  candidates: StickerAssetCandidate[];
  reason?: 'conversation_not_found';
}
