import type pg from 'pg';
import type { CapturedConversationInput, CapturedMessageInput } from '../types/chat.js';

type Metadata = Record<string, unknown>;
interface Conversation {
  id: number; user_id: string; platform: string; account_key: string; external_key: string;
  display_name: string | null; identity_confidence: number; merged_into_id: number | null;
}
interface Screenshot {
  id: string; user_id: string; device_id: string; platform: string; fingerprint: string;
  captured_at: string; occurred_at: string | null; metadata: Metadata; assets: string[];
  conversation: Conversation;
}
export interface ScreenshotRecoveryScope {
  userId: string; deviceId?: string; platform?: string; accountKey?: string;
  capturedAt?: string[]; occurredAt?: string[]; captureIds?: string[];
}
export interface ScreenshotRecoveryProposal {
  messageId: string; sourceConversationId: number; sourceExternalKey: string; targetConversationId: number;
  evidenceMessageIds: string[]; match: 'capture_id' | 'captured_at' | 'legacy_occurred_at';
  duplicateOfMessageId?: string;
}
export interface ScreenshotRecoveryReport {
  proposals: ScreenshotRecoveryProposal[];
  duplicates: { messageId: string; duplicateOfMessageId: string }[];
  blocked: { messageId: string; reason: 'missing_assets' | 'no_confirmed_match' | 'ambiguous_targets' | 'conflicting_recovery'; targetIds?: number[] }[];
}
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;
const screenshotSources = new Set(['wechat_empty_tree_screenshot', 'wechat_screenshot', 'wechat_page_screenshot',
  'qq_screenshot', 'douyin_screenshot', 'notification_screenshot_fallback']);
const identitySources = new Set(['on_device_title_ocr', 'accessibility_title', 'wechat_page_title']);

export function isScreenshotCapture(message: CapturedMessageInput): boolean {
  return message.message_type === 'image' && screenshotSources.has(String(message.metadata?.capture_source));
}
function trustedConfirmation(metadata: Metadata): boolean {
  return metadata.conversation_identity_status === 'confirmed'
    && screenshotSources.has(String(metadata.capture_source))
    && identitySources.has(String(metadata.conversation_identity_source));
}
function confirmed(c: Conversation): boolean {
  return !c.merged_into_id && c.identity_confidence >= .8 && !!c.display_name?.trim()
    && !c.display_name.trim().startsWith('待确认') && !c.external_key.startsWith('screenshot-v2:truncated:');
}
function pending(c: Conversation): boolean {
  return !c.merged_into_id && !c.external_key.startsWith('screenshot-v2:truncated:')
    && /^(screenshot-v2:|capture-v3:|screenshot-pending:|capture-pending:|notification-fallback-v2:|header:)/.test(c.external_key)
    && (c.identity_confidence < .8 || !!c.display_name?.trim().startsWith('待确认'));
}
function captureToken(m: Screenshot): string | null {
  const value = m.metadata.screenshot_capture_id;
  return typeof value === 'string' && uuid.test(value) ? value.toLowerCase() : null;
}
function legacyOccurrence(m: Screenshot): boolean {
  return m.platform === 'wechat' && m.metadata.capture_source === 'wechat_empty_tree_screenshot'
    && (m.metadata.conversation_identity_source === 'on_device_title_ocr' ||
      (m.metadata.conversation_identity_source === 'unresolved_title' && m.metadata.conversation_identity_status === 'pending'))
    && !!m.occurred_at;
}
function captureKeys(m: Screenshot): string[] {
  if (m.metadata.screenshot_capture_id !== undefined) {
    const token = captureToken(m);
    return token ? [`token:${token}`] : []; // 无效/单侧token不能退回时间猜测。
  }
  return [`captured:${m.captured_at}`, ...(legacyOccurrence(m) ? [`occurred:${m.occurred_at}`] : [])];
}
function sameCapture(a: Screenshot, b: Screenshot): ScreenshotRecoveryProposal['match'] | null {
  if (a.metadata.screenshot_capture_id !== undefined || b.metadata.screenshot_capture_id !== undefined) {
    return captureToken(a) !== null && captureToken(a) === captureToken(b) ? 'capture_id' : null;
  }
  if (a.captured_at === b.captured_at) return 'captured_at';
  return legacyOccurrence(a) && legacyOccurrence(b) && a.occurred_at === b.occurred_at ? 'legacy_occurred_at' : null;
}
const bucket = (m: Screenshot) => JSON.stringify([m.user_id, m.device_id, m.platform, m.conversation.account_key, m.assets]);

async function loadScreenshots(client: pg.PoolClient, scope: ScreenshotRecoveryScope): Promise<Screenshot[]> {
  const params: unknown[] = [scope.userId];
  const filters = ['m.user_id=$1', "m.message_type='image'", "COALESCE(m.metadata->>'screenshot_deleted','')<>'true'",
    "COALESCE(m.metadata->>'screenshot_assets_deleted','')<>'true'"];
  for (const [column, value] of [['m.device_id', scope.deviceId], ['m.platform', scope.platform], ['c.account_key', scope.accountKey]]) {
    if (value !== undefined) { params.push(value); filters.push(`${column}=$${params.length}`); }
  }
  const times: string[] = [];
  for (const [column, values, type] of [
    ['m.captured_at', scope.capturedAt, 'timestamptz'], ['m.occurred_at', scope.occurredAt, 'timestamptz'],
    ["m.metadata->>'screenshot_capture_id'", scope.captureIds, 'text'],
  ] as const) {
    if (values?.length) { params.push(values); times.push(`${column}=ANY($${params.length}::${type}[])`); }
  }
  if (times.length) filters.push(`(${times.join(' OR ')})`);
  const result = await client.query(`SELECT m.id,m.user_id,m.device_id,m.platform,m.fingerprint,
    m.captured_at::text AS captured_at,m.occurred_at::text AS occurred_at,m.metadata,
    c.id AS conversation_id,c.account_key,c.external_key,c.display_name,c.identity_confidence,c.merged_into_id,
    a.sha256,ma.role
    FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id AND c.user_id=m.user_id AND c.platform=m.platform
    LEFT JOIN chat_message_asset ma ON ma.message_id=m.id
    LEFT JOIN media_asset a ON a.id=ma.asset_id AND a.user_id=m.user_id
    WHERE ${filters.join(' AND ')} ORDER BY m.id,a.sha256,ma.role`, params);
  const messages = new Map<string, Screenshot>();
  for (const row of result.rows) {
    let m = messages.get(row.id);
    if (!m) {
      m = { id: row.id, user_id: row.user_id, device_id: row.device_id, platform: row.platform, fingerprint: row.fingerprint,
        captured_at: row.captured_at, occurred_at: row.occurred_at, metadata: row.metadata ?? {}, assets: [],
        conversation: { id: Number(row.conversation_id), user_id: row.user_id, platform: row.platform,
          account_key: row.account_key, external_key: row.external_key, display_name: row.display_name,
          identity_confidence: Number(row.identity_confidence), merged_into_id: row.merged_into_id ? Number(row.merged_into_id) : null } };
      messages.set(m.id, m);
    }
    if (row.sha256) m.assets.push(`${row.sha256}:${row.role}`);
    else if (row.role) m.assets.push('missing');
  }
  return [...messages.values()].filter(m => screenshotSources.has(String(m.metadata.capture_source)));
}

/** 只读取证据；调用方可用只读事务审核历史数据。返回ID/匹配理由，不返回聊天正文。 */
export async function auditPendingScreenshots(client: pg.PoolClient, scope: ScreenshotRecoveryScope,
  loaded?: Screenshot[]): Promise<ScreenshotRecoveryReport> {
  const messages = loaded ?? await loadScreenshots(client, scope);
  const targetIds = [...new Set(messages.flatMap(m => {
    const evidence = m.metadata.screenshot_confirmation_evidence;
    return Array.isArray(evidence) ? evidence.map(e => Number(e?.conversation_id)).filter(Number.isSafeInteger) : [];
  }))];
  const targets = new Map<number, Conversation>();
  if (targetIds.length) {
    const result = await client.query<Conversation>('SELECT * FROM chat_conversation WHERE user_id=$1 AND id=ANY($2::bigint[])', [scope.userId, targetIds]);
    for (const c of result.rows) targets.set(Number(c.id), { ...c, id: Number(c.id), identity_confidence: Number(c.identity_confidence) });
  }
  const evidence = new Map<string, { message: Screenshot; target: number }[]>();
  for (const m of messages) {
    if (!m.assets.length || m.assets.includes('missing')) continue;
    const manual = m.metadata.screenshot_manual_confirmation as Metadata | undefined;
    const ownConfirmed = confirmed(m.conversation) && (trustedConfirmation(m.metadata) ||
      (manual?.user_id === m.user_id && Number(manual.conversation_id) === m.conversation.id));
    const candidates = ownConfirmed ? [m.conversation.id] : [];
    const claims = m.metadata.screenshot_confirmation_evidence;
    if (Array.isArray(claims)) for (const claim of claims) {
      const c = targets.get(Number(claim?.conversation_id));
      if (c && c.platform === m.platform && c.account_key === m.conversation.account_key && confirmed(c)
        && trustedConfirmation(claim)) candidates.push(c.id);
    }
    for (const key of captureKeys(m)) {
      const full = `${bucket(m)}|${key}`;
      const items = evidence.get(full) ?? [];
      for (const target of new Set(candidates)) items.push({ message: m, target });
      evidence.set(full, items);
    }
  }
  const report: ScreenshotRecoveryReport = { proposals: [], duplicates: [], blocked: [] };
  for (const m of messages.filter(m => pending(m.conversation) || m.metadata.screenshot_identity_recovery)) {
    if (!m.assets.length || m.assets.includes('missing')) { report.blocked.push({ messageId: m.id, reason: 'missing_assets' }); continue; }
    const matches = captureKeys(m).flatMap(key => evidence.get(`${bucket(m)}|${key}`) ?? [])
      .map(item => ({ ...item, match: sameCapture(m, item.message) })).filter(item => item.match);
    const ids = [...new Set(matches.map(item => item.target))].sort((a, b) => a - b);
    if (!pending(m.conversation)) {
      if (ids.length > 1) report.blocked.push({ messageId: m.id, reason: 'conflicting_recovery', targetIds: ids });
      else if (ids.length === 1 && ids[0] === m.conversation.id && !m.metadata.screenshot_duplicate_of) {
        // 首图可能先以相同fingerprint完成确认，另一条确认记录稍后才到；保留原归属追踪。
        const canonical = matches.map(item => item.message).filter(item => item.id !== m.id
          && item.conversation.id === m.conversation.id && !item.metadata.screenshot_identity_recovery
          && !item.metadata.screenshot_duplicate_of).map(item => item.id).sort()[0];
        if (canonical) report.duplicates.push({ messageId: m.id, duplicateOfMessageId: canonical });
      }
      continue; // 后到冲突仅审计，不擅自撤销既有归属。
    }
    if (ids.length !== 1) { report.blocked.push({ messageId: m.id, reason: ids.length ? 'ambiguous_targets' : 'no_confirmed_match', ...(ids.length ? { targetIds: ids } : {}) }); continue; }
    const canonical = matches.map(item => item.message).filter(item => !item.metadata.screenshot_duplicate_of)
      .sort((a, b) => Number(pending(a.conversation)) - Number(pending(b.conversation)) || a.id.localeCompare(b.id))[0];
    const duplicateOf = canonical?.id !== m.id ? canonical?.id : undefined;
    report.proposals.push({ messageId: m.id, sourceConversationId: m.conversation.id, sourceExternalKey: m.conversation.external_key,
      targetConversationId: ids[0], evidenceMessageIds: [...new Set(matches.map(item => item.message.id))].sort(), match: matches[0].match!,
      ...(duplicateOf ? { duplicateOfMessageId: duplicateOf } : {}) });
  }
  return report;
}

/** 必须在调用方事务和 chat_conversation 同序锁内使用；只回填单条消息，不建立来源整体合并映射。 */
export async function recoverPendingScreenshots(client: pg.PoolClient, scope: ScreenshotRecoveryScope,
  loaded?: Screenshot[]): Promise<ScreenshotRecoveryReport & { moved: number }> {
  const report = await auditPendingScreenshots(client, scope, loaded);
  let moved = 0;
  for (const p of report.proposals) {
    const result = await client.query(`UPDATE chat_message SET conversation_id=$1,
      metadata=metadata || $2::jsonb
      WHERE id=$3 AND user_id=$4 AND conversation_id=$5`, [p.targetConversationId, JSON.stringify({
      ...(p.duplicateOfMessageId ? { screenshot_duplicate_of: p.duplicateOfMessageId } : {}),
      screenshot_identity_recovery: {
      source_conversation_id: p.sourceConversationId, source_external_key: p.sourceExternalKey,
      target_conversation_id: p.targetConversationId, evidence_message_ids: p.evidenceMessageIds, match: p.match,
    } }), p.messageId, scope.userId, p.sourceConversationId]);
    moved += result.rowCount ?? 0;
  }
  for (const p of report.duplicates) {
    await client.query(`UPDATE chat_message SET metadata=metadata || jsonb_build_object('screenshot_duplicate_of',$1::text)
      WHERE id=$2 AND user_id=$3 AND metadata ? 'screenshot_identity_recovery'
        AND NOT metadata ? 'screenshot_duplicate_of'`, [p.duplicateOfMessageId, p.messageId, scope.userId]);
  }
  return { ...report, moved };
}

/** 仅显式人工合并未知来源到同账号已确认目标时授予证据；普通合并不扩大自动归属。 */
export async function recordManualScreenshotConfirmation(client: pg.PoolClient, userId: string,
  sourceId: number, targetId: number, messageIds: string[]): Promise<ScreenshotRecoveryScope[]> {
  const result = await client.query<Conversation>('SELECT * FROM chat_conversation WHERE user_id=$1 AND id=ANY($2::bigint[])',
    [userId, [sourceId, targetId]]);
  const source = result.rows.find(c => Number(c.id) === sourceId);
  const target = result.rows.find(c => Number(c.id) === targetId);
  if (!source || !target || !pending(source) || !confirmed(target) || source.platform !== target.platform
    || source.account_key !== target.account_key || !messageIds.length) return [];
  const moved = await client.query(`UPDATE chat_message SET metadata=metadata || jsonb_build_object('screenshot_manual_confirmation',$1::jsonb)
    WHERE user_id=$2 AND id=ANY($3::uuid[]) AND conversation_id=$4 AND message_type='image'
      AND metadata->>'capture_source'=ANY($5::text[])
      RETURNING device_id,captured_at::text,occurred_at::text,metadata`,
  [JSON.stringify({ user_id: userId, source_conversation_id: sourceId, conversation_id: targetId }),
    userId, messageIds, targetId, [...screenshotSources]]);
  const scopes = new Map<string, ScreenshotRecoveryScope>();
  for (const row of moved.rows) {
    const s: ScreenshotRecoveryScope = scopes.get(row.device_id) ?? { userId, deviceId: row.device_id, platform: target.platform,
      accountKey: target.account_key, capturedAt: [], occurredAt: [], captureIds: [] };
    s.capturedAt!.push(row.captured_at);
    if (row.occurred_at) s.occurredAt!.push(row.occurred_at);
    if (typeof row.metadata.screenshot_capture_id === 'string') s.captureIds!.push(row.metadata.screenshot_capture_id);
    scopes.set(row.device_id, s);
  }
  return [...scopes.values()];
}

/** 确认重放可能命中旧fingerprint；只给完整同次截图记录确认凭证，不能提前跳过。 */
export async function recordScreenshotConfirmations(client: pg.PoolClient, scope: ScreenshotRecoveryScope,
  targetId: number, conversation: CapturedConversationInput, messages: CapturedMessageInput[]): Promise<Screenshot[] | undefined> {
  if (conversation.identity_confidence < .8) return;
  const confirmations = messages.filter(m => isScreenshotCapture(m) && trustedConfirmation(m.metadata ?? {}) && m.asset_sha256?.length);
  if (!confirmations.length) return;
  const existing = await loadScreenshots(client, scope);
  for (const input of confirmations) {
    const row = existing.find(m => m.fingerprint === input.fingerprint);
    if (!row || row.assets.join('|') !== [...new Set(input.asset_sha256)].sort().map(h => `${h}:content`).join('|')) continue;
    // 日期交由PostgreSQL保留微秒精度，禁止用JavaScript Date截断至毫秒后判等。
    const normalized = (await client.query(`SELECT $1::timestamptz::text AS captured_at,$2::timestamptz::text AS occurred_at`,
      [input.captured_at, input.occurred_at ?? null])).rows[0];
    if (!sameCapture(row, { ...row, ...normalized, metadata: input.metadata ?? {} })) continue;
    const claims = Array.isArray(row.metadata.screenshot_confirmation_evidence) ? row.metadata.screenshot_confirmation_evidence : [];
    if (claims.some(c => Number(c?.conversation_id) === targetId)) continue;
    const claim = { conversation_id: targetId, capture_source: input.metadata?.capture_source,
      conversation_identity_status: 'confirmed', conversation_identity_source: input.metadata?.conversation_identity_source };
    const next = [...claims, claim];
    await client.query(`UPDATE chat_message SET metadata=metadata || jsonb_build_object('screenshot_confirmation_evidence',$1::jsonb)
      WHERE id=$2 AND user_id=$3`, [JSON.stringify(next), row.id, scope.userId]);
    row.metadata = { ...row.metadata, screenshot_confirmation_evidence: next };
  }
  return existing;
}
