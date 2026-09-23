import type pg from 'pg';
import { pendingConversation, truncatedConversation } from './chatPending.js';

type Message = { id: unknown; conversation_id: unknown; metadata?: Record<string, unknown> };
type Source = { id: string; platform: string; account_key: string };
export interface PendingDiagnostic {
  reason: 'title_unreadable' | 'title_unconfirmed' | 'possible_existing_conversation' | 'non_chat_page';
  observed_title: string | null;
  identity_status: string | null;
  identity_source: string | null;
  suggested_conversations: Array<{ id: number; display_name: string }>;
}
const text = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim().slice(0, 500) : null;

/** 当前页批量读取证据；名称相同仅作人工建议，绝不改动消息归属。 */
export async function pendingMessageDiagnostics(pool: pg.Pool, userId: string, messages: Message[]) {
  const result = new Map<string, PendingDiagnostic>();
  if (!messages.length) return result;
  const ids = [...new Set(messages.map(message => String(message.conversation_id)))];
  const sources = await pool.query<Source>(`SELECT c.id,c.platform,c.account_key FROM chat_conversation c
    WHERE c.user_id=$1 AND c.id=ANY($2::bigint[]) AND ${pendingConversation()}`, [userId, ids]);
  const byId = new Map(sources.rows.map(source => [String(source.id), source]));
  const pending = messages.filter(message => byId.has(String(message.conversation_id)));
  const titleOf = (message: Message) => text(message.metadata?.conversation_identity_observed_title) ?? text(message.metadata?.conversation_title_observed);
  const names = [...new Set(pending.map(titleOf).filter((name): name is string => name !== null))];
  const candidates = names.length ? (await pool.query<Source & { display_name: string }>(
    `SELECT c.id,c.platform,c.account_key,btrim(c.display_name) AS display_name FROM chat_conversation c
      WHERE c.user_id=$1 AND c.merged_into_id IS NULL AND c.identity_confidence>=0.8
        AND NOT (${pendingConversation()}) AND NOT (${truncatedConversation()})
        AND btrim(c.display_name)=ANY($2::text[]) ORDER BY c.id`, [userId, names],
  )).rows : [];
  for (const message of pending) {
    const source = byId.get(String(message.conversation_id))!;
    const metadata = message.metadata ?? {};
    const title = titleOf(message);
    const identitySource = text(metadata.conversation_identity_source);
    const nonChat = metadata.conversation_identity_page_type === 'non_chat' ||
      (source.platform === 'wechat' && source.account_key === 'wechat-empty-tree' && identitySource === 'wechat_page_title' && title === '发现');
    const suggestions = nonChat ? [] : candidates.filter(candidate =>
      candidate.platform === source.platform && candidate.account_key === source.account_key &&
      candidate.display_name === title && String(candidate.id) !== String(source.id),
    ).map(candidate => ({ id: Number(candidate.id), display_name: candidate.display_name }));
    result.set(String(message.id), {
      reason: nonChat ? 'non_chat_page' : !title ? 'title_unreadable' : suggestions.length ? 'possible_existing_conversation' : 'title_unconfirmed',
      observed_title: title,
      identity_status: text(metadata.conversation_identity_status),
      identity_source: identitySource,
      suggested_conversations: suggestions,
    });
  }
  return result;
}
