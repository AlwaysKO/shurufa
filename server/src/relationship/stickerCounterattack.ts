import type pg from 'pg';
import type {
  IncomingStickerAsset,
  RelationshipConversationIdentity,
  RelationshipType,
  StickerAssetCandidate,
  StickerCandidateResult,
  StickerCandidateSource,
} from '../types/relationship.js';

const MAX_MESSAGES_PER_LAYER = 2_000;

interface ConversationRow {
  id: string | number;
  relationship_type: RelationshipType | null;
}

interface StickerMessageRow {
  message_id: string;
  conversation_id: string | number;
  direction: 'incoming' | 'outgoing' | 'system';
  occurred_at: Date | string | null;
  captured_at: Date | string;
  asset_id: string | number;
  sha256: string;
  mime_type: string;
  storage_path: string;
  width: number | null;
  height: number | null;
}

function usedAt(row: StickerMessageRow): string {
  return new Date(row.occurred_at ?? row.captured_at).toISOString();
}

function assetUrl(storagePath: string): string {
  return `/uploads/${storagePath.replace(/^\/+/, '')}`;
}

function aggregate(
  rows: StickerMessageRow[],
  source: StickerCandidateSource,
): StickerAssetCandidate[] {
  const grouped = new Map<string, StickerAssetCandidate>();
  for (const row of rows) {
    const timestamp = usedAt(row);
    const existing = grouped.get(row.sha256);
    if (!existing) {
      grouped.set(row.sha256, {
        asset_id: Number(row.asset_id),
        sha256: row.sha256,
        mime_type: row.mime_type,
        width: row.width,
        height: row.height,
        url: assetUrl(row.storage_path),
        source,
        use_count: 1,
        last_used_at: timestamp,
      });
      continue;
    }
    existing.use_count += 1;
    if (timestamp > existing.last_used_at) {
      existing.asset_id = Number(row.asset_id);
      existing.mime_type = row.mime_type;
      existing.width = row.width;
      existing.height = row.height;
      existing.url = assetUrl(row.storage_path);
      existing.last_used_at = timestamp;
    }
  }
  return [...grouped.values()].sort((left, right) =>
    right.use_count - left.use_count
      || right.last_used_at.localeCompare(left.last_used_at)
      || left.sha256.localeCompare(right.sha256));
}

function outgoing(rows: StickerMessageRow[]): StickerMessageRow[] {
  return rows.filter((row) => row.direction === 'outgoing');
}

function counterattacks(
  rowsDescending: StickerMessageRow[],
  incomingSha256: string | null,
): StickerMessageRow[] {
  if (!incomingSha256) return [];
  const rows = [...rowsDescending].reverse();
  const matches: StickerMessageRow[] = [];
  for (let index = 0; index < rows.length - 1; index += 1) {
    const incoming = rows[index];
    const reply = rows[index + 1];
    if (incoming.direction === 'incoming'
      && incoming.sha256 === incomingSha256
      && reply.direction === 'outgoing') {
      matches.push(reply);
    }
  }
  return matches;
}

async function stickerMessages(
  pool: pg.Pool,
  whereSql: string,
  params: unknown[],
  joinSql = '',
): Promise<StickerMessageRow[]> {
  const result = await pool.query<StickerMessageRow>(
    `SELECT m.id AS message_id, m.conversation_id, m.direction,
            m.occurred_at, m.captured_at,
            a.id AS asset_id, a.sha256, a.mime_type, a.storage_path,
            a.width, a.height
     FROM chat_message m
     JOIN chat_message_asset ma
       ON ma.message_id = m.id AND ma.role = 'content' AND ma.position = 0
     JOIN media_asset a
       ON a.id = ma.asset_id AND a.user_id = m.user_id
     ${joinSql}
     WHERE m.message_type IN ('emoji', 'sticker') AND ${whereSql}
     ORDER BY m.captured_at DESC, m.id DESC
     LIMIT $${params.length + 1}`,
    [...params, MAX_MESSAGES_PER_LAYER],
  );
  return result.rows;
}

export async function getStickerCounterattackCandidates(
  pool: pg.Pool,
  userId: string,
  identity: RelationshipConversationIdentity,
  incomingSha256: string | null,
  limit: number,
): Promise<StickerCandidateResult> {
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
      incoming_asset_sha256: incomingSha256,
      candidates: [],
      reason: 'conversation_not_found',
    };
  }

  const conversationId = Number(conversation.id);
  const relationshipType = conversation.relationship_type ?? 'unknown';
  const currentMessages = await stickerMessages(
    pool,
    'm.user_id = $1 AND m.conversation_id = $2',
    [userId, conversationId],
  );
  const relationshipMessages = relationshipType === 'unknown'
    ? []
    : await stickerMessages(
      pool,
      `m.user_id = $1 AND m.conversation_id <> $2
       AND r.relationship_type = $3`,
      [userId, conversationId, relationshipType],
      `JOIN relationship_profile r
         ON r.user_id = m.user_id AND r.conversation_id = m.conversation_id`,
    );
  const globalMessages = await stickerMessages(pool, 'm.user_id = $1', [userId]);

  const layers: StickerAssetCandidate[][] = [
    aggregate(counterattacks(currentMessages, incomingSha256), 'sticker_counterattack'),
    aggregate(outgoing(currentMessages), 'sticker_conversation_frequency'),
    aggregate(outgoing(relationshipMessages), 'sticker_relationship_type_frequency'),
    aggregate(outgoing(globalMessages), 'sticker_global_frequency'),
  ];
  const seen = new Set<string>();
  const candidates: StickerAssetCandidate[] = [];
  for (const layer of layers) {
    for (const candidate of layer) {
      if (seen.has(candidate.sha256)) continue;
      seen.add(candidate.sha256);
      candidates.push(candidate);
      if (candidates.length >= limit) break;
    }
    if (candidates.length >= limit) break;
  }

  return {
    conversation_id: conversationId,
    relationship_type: relationshipType,
    incoming_asset_sha256: incomingSha256,
    candidates,
  };
}

export async function getRecentIncomingStickerAssets(
  pool: pg.Pool,
  userId: string,
  conversationId: number,
  limit: number,
): Promise<IncomingStickerAsset[]> {
  const rows = await stickerMessages(
    pool,
    `m.user_id = $1 AND m.conversation_id = $2
     AND m.direction = 'incoming'`,
    [userId, conversationId],
  );
  const seen = new Set<string>();
  const assets: IncomingStickerAsset[] = [];
  for (const row of rows) {
    if (seen.has(row.sha256)) continue;
    seen.add(row.sha256);
    assets.push({
      asset_id: Number(row.asset_id),
      sha256: row.sha256,
      mime_type: row.mime_type,
      width: row.width,
      height: row.height,
      url: assetUrl(row.storage_path),
      last_seen_at: usedAt(row),
    });
    if (assets.length >= limit) break;
  }
  return assets;
}
