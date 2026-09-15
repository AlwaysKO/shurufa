import type pg from 'pg';
import type {
  CapturedConversationInput,
  CapturedMessageInput,
} from '../types/chat.js';

export interface IngestCapturedMessagesResult {
  conversationId: number;
  inserted: number;
  duplicated: number;
  missingAssets: string[];
}

interface AssetRow {
  id: string | number;
  sha256: string;
}

interface RecentCallState {
  senderKey: string;
  text: string;
  capturedAtMillis: number;
}

const CALL_STATE_DEDUP_WINDOW_MS = 5 * 60 * 1000;

function isWechatCallState(
  conversation: CapturedConversationInput,
  message: CapturedMessageInput,
): boolean {
  const source = message.metadata?.capture_source;
  return conversation.platform === 'wechat'
    && message.direction === 'incoming'
    && source === 'notification'
    && /^(视频|语音)通话/.test(message.text?.trim() ?? '');
}

function isRecentDuplicateCallState(
  recent: RecentCallState[],
  message: CapturedMessageInput,
): boolean {
  const capturedAtMillis = Date.parse(message.captured_at);
  return recent.some((item) => item.senderKey === message.sender_key
    && item.text === message.text?.trim()
    && Math.abs(item.capturedAtMillis - capturedAtMillis) <= CALL_STATE_DEDUP_WINDOW_MS);
}

function uniqueAssetHashes(messages: CapturedMessageInput[]): string[] {
  return [...new Set(messages.flatMap((message) => message.asset_sha256 ?? []))];
}

async function findAssets(
  client: pg.PoolClient,
  userId: string,
  hashes: string[],
): Promise<Map<string, number>> {
  if (hashes.length === 0) return new Map();
  const placeholders = hashes.map((_, index) => `$${index + 2}`).join(', ');
  const result = await client.query<AssetRow>(
    `SELECT id, sha256 FROM media_asset
     WHERE user_id = $1 AND sha256 IN (${placeholders})`,
    [userId, ...hashes],
  );
  return new Map(result.rows.map((row) => [row.sha256, Number(row.id)]));
}

async function findExistingFingerprints(
  client: pg.PoolClient,
  userId: string,
  platform: CapturedConversationInput['platform'],
  messages: CapturedMessageInput[],
): Promise<Set<string>> {
  const fingerprints = [...new Set(messages.map((message) => message.fingerprint))];
  if (fingerprints.length === 0) return new Set();
  const placeholders = fingerprints.map((_, index) => `$${index + 3}`).join(', ');
  const result = await client.query<{ fingerprint: string }>(
    `SELECT fingerprint FROM chat_message
     WHERE user_id = $1 AND platform = $2 AND fingerprint IN (${placeholders})`,
    [userId, platform, ...fingerprints],
  );
  return new Set(result.rows.map((row) => row.fingerprint));
}

export async function ingestCapturedMessages(
  pool: pg.Pool,
  userId: string,
  deviceId: string,
  conversation: CapturedConversationInput,
  messages: CapturedMessageInput[],
): Promise<IngestCapturedMessagesResult> {
  if (messages.length > 200) {
    throw new Error('单批消息不得超过 200 条');
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const conversationResult = await client.query<{ id: string | number }>(
      `INSERT INTO chat_conversation
        (user_id, platform, account_key, external_key, display_name,
         conversation_type, identity_confidence)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (user_id, platform, account_key, external_key)
       DO UPDATE SET
         display_name = COALESCE(EXCLUDED.display_name, chat_conversation.display_name),
         conversation_type = EXCLUDED.conversation_type,
         identity_confidence = EXCLUDED.identity_confidence,
         last_seen_at = NOW()
       RETURNING id`,
      [
        userId,
        conversation.platform,
        conversation.account_key,
        conversation.external_key,
        conversation.display_name ?? null,
        conversation.conversation_type,
        conversation.identity_confidence,
      ],
    );
    const conversationId = Number(conversationResult.rows[0].id);

    const requiredAssets = uniqueAssetHashes(messages);
    const assetsByHash = await findAssets(client, userId, requiredAssets);
    const missingAssets = requiredAssets.filter((sha256) => !assetsByHash.has(sha256));
    const missingSet = new Set(missingAssets);
    const existingFingerprints = await findExistingFingerprints(
      client,
      userId,
      conversation.platform,
      messages,
    );
    const callStateMessages = messages.filter((message) => isWechatCallState(conversation, message));
    const callStateTimes = callStateMessages.map((message) => Date.parse(message.captured_at)).filter(Number.isFinite);
    const recentCallStates: RecentCallState[] = [];
    if (callStateTimes.length > 0) {
      const oldest = new Date(Math.min(...callStateTimes) - CALL_STATE_DEDUP_WINDOW_MS).toISOString();
      const newest = new Date(Math.max(...callStateTimes) + CALL_STATE_DEDUP_WINDOW_MS).toISOString();
      const existingCallStates = await client.query<{
        sender_key: string; text: string; captured_at: Date | string;
      }>(`SELECT sender_key, text, captured_at FROM chat_message
          WHERE user_id = $1 AND conversation_id = $2
            AND direction = 'incoming' AND captured_at BETWEEN $3 AND $4
            AND metadata->>'capture_source' = 'notification'
            AND (text LIKE '视频通话%' OR text LIKE '语音通话%')`,
      [userId, conversationId, oldest, newest]);
      recentCallStates.push(...existingCallStates.rows.map((row) => ({
        senderKey: row.sender_key,
        text: row.text.trim(),
        capturedAtMillis: new Date(row.captured_at).getTime(),
      })));
    }

    let inserted = 0;
    let duplicated = 0;
    for (const message of messages) {
      const messageAssets = [...new Set(message.asset_sha256 ?? [])];
      if (messageAssets.some((sha256) => missingSet.has(sha256))) continue;
      if (existingFingerprints.has(message.fingerprint)) {
        duplicated += 1;
        continue;
      }
      if (isWechatCallState(conversation, message) && isRecentDuplicateCallState(recentCallStates, message)) {
        duplicated += 1;
        continue;
      }

      const result = await client.query<{ id: string }>(
        `INSERT INTO chat_message
          (id, user_id, device_id, conversation_id, platform, fingerprint,
           content_fingerprint, sender_key, sender_name, direction, message_type,
           text, displayed_time, occurred_at, captured_at, sequence_hint, metadata)
         VALUES
          ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13,
           $14, $15, $16, $17)
         ON CONFLICT (user_id, platform, fingerprint) DO NOTHING
         RETURNING id`,
        [
          message.id,
          userId,
          deviceId,
          conversationId,
          conversation.platform,
          message.fingerprint,
          message.content_fingerprint,
          message.sender_key,
          message.sender_name ?? null,
          message.direction,
          message.message_type,
          message.text ?? null,
          message.displayed_time ?? null,
          message.occurred_at ?? null,
          message.captured_at,
          message.sequence_hint ?? null,
          JSON.stringify(message.metadata ?? {}),
        ],
      );

      existingFingerprints.add(message.fingerprint);
      if (result.rows.length === 0) {
        duplicated += 1;
        continue;
      }
      inserted += 1;
      if (isWechatCallState(conversation, message)) {
        recentCallStates.push({
          senderKey: message.sender_key,
          text: message.text?.trim() ?? '',
          capturedAtMillis: Date.parse(message.captured_at),
        });
      }
      for (const [position, sha256] of messageAssets.entries()) {
        await client.query(
          `INSERT INTO chat_message_asset (message_id, asset_id, role, position)
           VALUES ($1, $2, 'content', $3)
           ON CONFLICT (message_id, asset_id, role) DO NOTHING`,
          [message.id, assetsByHash.get(sha256), position],
        );
      }
    }

    await client.query('COMMIT');
    return { conversationId, inserted, duplicated, missingAssets };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}
