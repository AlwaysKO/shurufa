import type pg from 'pg';
import type {
  RelationshipConversationIdentity,
  RelationshipType,
  ZeroTokenCandidate,
  ZeroTokenCandidateResult,
  ZeroTokenCandidateSource,
} from '../types/relationship.js';

const MAX_MESSAGES_PER_LAYER = 2_000;
const MAX_CANDIDATE_LENGTH = 500;

interface ConversationRow {
  id: string | number;
  relationship_type: RelationshipType | null;
}

interface MessageRow {
  conversation_id: string | number;
  direction: 'incoming' | 'outgoing' | 'system';
  message_type: string;
  text: string | null;
  occurred_at: Date | string | null;
  captured_at: Date | string;
}

interface CandidateOccurrence {
  text: string;
  usedAt: string;
}

function normalizeText(text: string): string {
  return text.trim().replace(/\s+/gu, ' ');
}

function usedAt(row: MessageRow): string {
  return new Date(row.occurred_at ?? row.captured_at).toISOString();
}

function eligibleOutgoing(row: MessageRow): boolean {
  return row.direction === 'outgoing'
    && row.message_type === 'text'
    && row.text !== null
    && normalizeText(row.text).length > 0
    && row.text.length <= MAX_CANDIDATE_LENGTH;
}

function aggregate(
  occurrences: CandidateOccurrence[],
  source: ZeroTokenCandidateSource,
): ZeroTokenCandidate[] {
  const grouped = new Map<string, ZeroTokenCandidate>();
  for (const occurrence of occurrences) {
    const key = normalizeText(occurrence.text);
    if (!key) continue;
    const existing = grouped.get(key);
    if (!existing) {
      grouped.set(key, {
        text: occurrence.text,
        source,
        use_count: 1,
        last_used_at: occurrence.usedAt,
      });
      continue;
    }
    existing.use_count += 1;
    if (occurrence.usedAt > existing.last_used_at) {
      existing.text = occurrence.text;
      existing.last_used_at = occurrence.usedAt;
    }
  }
  return [...grouped.values()].sort((left, right) =>
    right.use_count - left.use_count
      || right.last_used_at.localeCompare(left.last_used_at)
      || left.text.localeCompare(right.text, 'zh-CN'));
}

function outgoingOccurrences(rows: MessageRow[]): CandidateOccurrence[] {
  return rows.filter(eligibleOutgoing).map((row) => ({
    text: row.text as string,
    usedAt: usedAt(row),
  }));
}

function contextOccurrences(rowsDescending: MessageRow[], contextText: string): CandidateOccurrence[] {
  const normalizedContext = normalizeText(contextText);
  if (!normalizedContext) return [];
  const rows = [...rowsDescending].reverse();
  const matches: CandidateOccurrence[] = [];
  for (let index = 0; index < rows.length - 1; index += 1) {
    const incoming = rows[index];
    const reply = rows[index + 1];
    if (incoming.direction !== 'incoming' || incoming.message_type !== 'text' || incoming.text === null) {
      continue;
    }
    if (normalizeText(incoming.text) !== normalizedContext || !eligibleOutgoing(reply)) continue;
    matches.push({ text: reply.text as string, usedAt: usedAt(reply) });
  }
  return matches;
}

async function messages(
  pool: pg.Pool,
  sql: string,
  params: unknown[],
): Promise<MessageRow[]> {
  const result = await pool.query<MessageRow>(sql, params);
  return result.rows;
}

export async function getZeroTokenCandidates(
  pool: pg.Pool,
  userId: string,
  identity: RelationshipConversationIdentity,
  contextText: string,
  limit: number,
): Promise<ZeroTokenCandidateResult> {
  const conversationResult = await pool.query<ConversationRow>(
    `SELECT c.id, r.relationship_type
     FROM chat_conversation c
     LEFT JOIN relationship_profile r
       ON r.user_id = c.user_id AND r.conversation_id = c.id
     WHERE c.user_id = $1 AND c.platform = $2
       AND c.account_key = $3 AND c.external_key = $4`,
    [userId, identity.platform, identity.account_key, identity.external_key],
  );
  const conversation = conversationResult.rows[0];
  if (!conversation) {
    return {
      conversation_id: null,
      relationship_type: 'unknown',
      candidates: [],
      reason: 'conversation_not_found',
    };
  }

  const conversationId = Number(conversation.id);
  const relationshipType = conversation.relationship_type ?? 'unknown';
  const currentMessages = await messages(
    pool,
    `SELECT conversation_id, direction, message_type, text, occurred_at, captured_at
     FROM chat_message
     WHERE user_id = $1 AND conversation_id = $2
     ORDER BY captured_at DESC, id DESC
     LIMIT $3`,
    [userId, conversationId, MAX_MESSAGES_PER_LAYER],
  );
  const relationshipMessages = relationshipType === 'unknown'
    ? []
    : await messages(
      pool,
      `SELECT m.conversation_id, m.direction, m.message_type, m.text,
              m.occurred_at, m.captured_at
       FROM chat_message m
       JOIN relationship_profile r
         ON r.user_id = m.user_id AND r.conversation_id = m.conversation_id
       WHERE m.user_id = $1 AND r.relationship_type = $2
         AND m.conversation_id <> $3
       ORDER BY m.captured_at DESC, m.id DESC
       LIMIT $4`,
      [userId, relationshipType, conversationId, MAX_MESSAGES_PER_LAYER],
    );
  const globalMessages = await messages(
    pool,
    `SELECT conversation_id, direction, message_type, text, occurred_at, captured_at
     FROM chat_message
     WHERE user_id = $1
     ORDER BY captured_at DESC, id DESC
     LIMIT $2`,
    [userId, MAX_MESSAGES_PER_LAYER],
  );

  const layers: ZeroTokenCandidate[][] = [
    aggregate(contextOccurrences(currentMessages, contextText), 'context_match'),
    aggregate(outgoingOccurrences(currentMessages), 'conversation_frequency'),
    aggregate(outgoingOccurrences(relationshipMessages), 'relationship_type_frequency'),
    aggregate(outgoingOccurrences(globalMessages), 'global_frequency'),
  ];
  const seen = new Set<string>();
  const candidates: ZeroTokenCandidate[] = [];
  for (const layer of layers) {
    for (const candidate of layer) {
      const key = normalizeText(candidate.text);
      if (seen.has(key)) continue;
      seen.add(key);
      candidates.push(candidate);
      if (candidates.length >= limit) break;
    }
    if (candidates.length >= limit) break;
  }

  return {
    conversation_id: conversationId,
    relationship_type: relationshipType,
    candidates,
  };
}
