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

function isUnusableWechatNotification(
  conversation: CapturedConversationInput,
  message: CapturedMessageInput,
): boolean {
  if (conversation.platform !== 'wechat'
    || conversation.display_name?.trim() !== '微信'
    || message.metadata?.capture_source !== 'notification') return false;
  const text = message.text?.trim() ?? '';
  return /(?:[\d一二三四五六七八九十]+)\s*个联系人.*(?:[\d一二三四五六七八九十]+)\s*条(?:新)?消息/.test(text)
    || /登录\s*(?:Windows|Mac)\s*微信/i.test(text);
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
    await client.query('LOCK TABLE chat_conversation IN SHARE ROW EXCLUSIVE MODE');
    // 新版会话的待确认重试可能晚到，不能把已确认名称降级；旧标识的更新规则保持不变。
    const conversationResult = await client.query<{ id: string | number; merged_into_id: string | number | null }>(
      `INSERT INTO chat_conversation
        (user_id, platform, account_key, external_key, display_name,
         conversation_type, identity_confidence)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (user_id, platform, account_key, external_key)
       DO UPDATE SET
         display_name = CASE WHEN (EXCLUDED.external_key LIKE 'screenshot-v2:%' OR EXCLUDED.external_key LIKE 'capture-v3:%'
           OR EXCLUDED.external_key LIKE 'notification-v2:%')
           AND chat_conversation.identity_confidence >= 0.8 AND EXCLUDED.identity_confidence < 0.8
           THEN chat_conversation.display_name
           ELSE COALESCE(EXCLUDED.display_name, chat_conversation.display_name) END,
         conversation_type = CASE WHEN (EXCLUDED.external_key LIKE 'screenshot-v2:%' OR EXCLUDED.external_key LIKE 'capture-v3:%'
           OR EXCLUDED.external_key LIKE 'notification-v2:%')
           AND chat_conversation.identity_confidence >= 0.8 AND EXCLUDED.identity_confidence < 0.8
           THEN chat_conversation.conversation_type ELSE EXCLUDED.conversation_type END,
         identity_confidence = CASE WHEN (EXCLUDED.external_key LIKE 'screenshot-v2:%' OR EXCLUDED.external_key LIKE 'capture-v3:%'
           OR EXCLUDED.external_key LIKE 'notification-v2:%')
           AND chat_conversation.identity_confidence >= 0.8 AND EXCLUDED.identity_confidence < 0.8
           THEN chat_conversation.identity_confidence ELSE EXCLUDED.identity_confidence END,
         last_seen_at = NOW()
       RETURNING id, merged_into_id`,
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
    const conversationId = Number(conversationResult.rows[0].merged_into_id ?? conversationResult.rows[0].id);

    if (conversationResult.rows[0].merged_into_id) await client.query('UPDATE chat_conversation SET last_seen_at=NOW() WHERE id=$1 AND user_id=$2', [conversationId, userId]);

    // 仅接续端侧同一次页面中产生的临时身份；不按名字、任意历史key或已确认身份自动合并。
    if (conversation.identity_confidence >= 0.8 && /^(screenshot-v2|capture-v3):/.test(conversation.external_key)) {
      const previousKeys = [...new Set(messages.filter(m => m.direction === 'system' && m.message_type === 'image' && m.metadata?.conversation_identity_status === 'confirmed')
        .map(m => m.metadata?.conversation_identity_previous_key)
        .filter((key): key is string => typeof key === 'string' && /^(screenshot-v2|capture-v3):pending:[a-f0-9-]{36}$/.test(key)))];
      for (const key of previousKeys) {
        if (key === conversation.external_key) continue;
        const previous = (await client.query<{id:string; merged_into_id:string|null; identity_confidence:string}>(`INSERT INTO chat_conversation
          (user_id,platform,account_key,external_key,display_name,conversation_type,identity_confidence,merged_into_id)
          VALUES($1,$2,$3,$4,'待确认会话','unknown',0,$5)
          ON CONFLICT(user_id,platform,account_key,external_key) DO UPDATE SET external_key=EXCLUDED.external_key
          RETURNING id,merged_into_id,identity_confidence`, [userId,conversation.platform,conversation.account_key,key,conversationId])).rows[0];
        if (Number(previous.id) === conversationId || Number(previous.identity_confidence) >= 0.8 || (previous.merged_into_id && Number(previous.merged_into_id) !== conversationId)) continue;
        // pending本身可能已是一次人工合并的目标；确认时必须连同其所有来源一起展平。
        await client.query(`UPDATE chat_message SET conversation_id=$1 WHERE user_id=$2 AND conversation_id IN
          (SELECT id FROM chat_conversation WHERE user_id=$2 AND (id=$3 OR merged_into_id=$3))`, [conversationId,userId,previous.id]);
        await client.query('UPDATE chat_conversation SET merged_into_id=$1 WHERE user_id=$2 AND (id=$3 OR merged_into_id=$3)', [conversationId,userId,previous.id]);
      }
    }

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
    let discarded = 0;
    for (const message of messages) {
      const messageAssets = [...new Set(message.asset_sha256 ?? [])];
      if (messageAssets.some((sha256) => missingSet.has(sha256))) continue;
      if (isUnusableWechatNotification(conversation, message)) {
        duplicated += 1;
        discarded += 1;
        continue;
      }
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

    if (discarded === messages.length) {
      await client.query(
        `DELETE FROM chat_conversation WHERE id=$1 AND user_id=$2
         AND merged_into_id IS NULL
         AND NOT EXISTS (SELECT 1 FROM chat_conversation WHERE merged_into_id=$1)
         AND NOT EXISTS (SELECT 1 FROM chat_message WHERE conversation_id=$1)`,
        [conversationId, userId],
      );
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
