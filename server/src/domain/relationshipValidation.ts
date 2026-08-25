import { CHAT_PLATFORMS } from '../types/chat.js';
import {
  RELATIONSHIP_TYPES,
  type RelationshipCandidateQuery,
  type RelationshipProfileInput,
  type StickerCandidateQuery,
} from '../types/relationship.js';

const SHA256_PATTERN = /^[a-f0-9]{64}$/;

function object(input: unknown, name: string): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new Error(`${name} must be an object`);
  }
  return input as Record<string, unknown>;
}

function requiredString(
  value: unknown,
  name: string,
  maxLength: number,
): string {
  if (typeof value !== 'string' || value.trim().length === 0 || value.trim().length > maxLength) {
    throw new Error(`${name} is invalid`);
  }
  return value.trim();
}

function optionalString(
  value: unknown,
  name: string,
  maxLength: number,
): string | null | undefined {
  if (value === undefined) return undefined;
  if (value === null) return null;
  if (typeof value !== 'string' || value.length > maxLength) {
    throw new Error(`${name} is invalid`);
  }
  return value;
}

function level(value: unknown, name: string): number {
  if (!Number.isInteger(value) || Number(value) < 0 || Number(value) > 100) {
    throw new Error(`${name} is invalid`);
  }
  return Number(value);
}

export function validateRelationshipProfileInput(input: unknown): RelationshipProfileInput {
  const value = object(input, 'profile');
  if (!RELATIONSHIP_TYPES.includes(value.relationship_type as never)) {
    throw new Error('relationship_type is invalid');
  }
  return {
    relationship_type: value.relationship_type as RelationshipProfileInput['relationship_type'],
    alias: optionalString(value.alias, 'alias', 100),
    intimacy_level: level(value.intimacy_level, 'intimacy_level'),
    humor_level: level(value.humor_level, 'humor_level'),
    notes: optionalString(value.notes, 'notes', 2_000),
  };
}

export function validateRelationshipCandidateQuery(input: unknown): RelationshipCandidateQuery {
  const value = object(input, 'query');
  if (!CHAT_PLATFORMS.includes(value.platform as never)) {
    throw new Error('platform is invalid');
  }
  const rawLimit = value.limit ?? 6;
  if (!Number.isInteger(rawLimit) || Number(rawLimit) < 1 || Number(rawLimit) > 20) {
    throw new Error('limit is invalid');
  }
  if (value.context_text !== undefined && typeof value.context_text !== 'string') {
    throw new Error('context_text is invalid');
  }
  if (typeof value.context_text === 'string' && value.context_text.length > 20_000) {
    throw new Error('context_text is invalid');
  }
  return {
    identity: {
      platform: value.platform as RelationshipCandidateQuery['identity']['platform'],
      account_key: requiredString(value.account_key, 'account_key', 500),
      external_key: requiredString(value.external_key, 'external_key', 500),
    },
    contextText: (value.context_text as string | undefined) ?? '',
    limit: Number(rawLimit),
  };
}

export function validateStickerCandidateQuery(input: unknown): StickerCandidateQuery {
  const value = object(input, 'query');
  const common = validateRelationshipCandidateQuery({
    platform: value.platform,
    account_key: value.account_key,
    external_key: value.external_key,
    limit: value.limit,
  });
  const rawSha256 = value.incoming_asset_sha256;
  let incomingAssetSha256: string | null = null;
  if (rawSha256 !== undefined && rawSha256 !== null && rawSha256 !== '') {
    if (typeof rawSha256 !== 'string' || !SHA256_PATTERN.test(rawSha256)) {
      throw new Error('incoming_asset_sha256 is invalid');
    }
    incomingAssetSha256 = rawSha256;
  }
  return {
    identity: common.identity,
    incomingAssetSha256,
    limit: common.limit,
  };
}
