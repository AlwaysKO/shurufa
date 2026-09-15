import type { RelationshipConversationIdentity, ZeroTokenCandidate } from './relationship.js';

export interface RelationshipAiProfileDocument {
  summary: string;
  user_style: string[];
  peer_style: string[];
  relationship_signals: string[];
  preferred_tone: string[];
  avoid: string[];
  evidence_message_ids: string[];
}

export interface AiReplyQuery {
  identity: RelationshipConversationIdentity;
  contextText: string;
  refreshCount: number;
  limit: number;
  sessionId: string | null;
}

export type ReplyCandidateSource = 'zero_token' | 'ai';

export interface RelationshipReplyCandidate {
  text: string;
  source: ReplyCandidateSource;
}

export interface RelationshipReplyResult {
  conversation_id: number | null;
  candidates: RelationshipReplyCandidate[];
  ai_used: boolean;
  profile_version: number | null;
  fallback_reason?: 'conversation_not_found' | 'refresh_threshold' | 'profile_missing' | 'ai_unavailable' | 'budget_exceeded';
  zero_token_candidates?: ZeroTokenCandidate[];
}
