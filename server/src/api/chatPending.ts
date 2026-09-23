import { visibleChatMessage } from '../chat/chatMessageVisibility.js';
import { Router } from 'express';
import type pg from 'pg';

export const chatPlatforms = ['wechat', 'qq', 'douyin'];
/** 历史OCR固定页面别名仅作用于微信截图来源；不改库、不模糊合并真实联系人。 */
export function conversationGroupName(alias = 'c'): string {
  const name = `btrim(${alias}.display_name)`;
  return `(CASE WHEN ${alias}.platform='wechat' AND ${alias}.account_key='wechat-empty-tree' THEN
    CASE WHEN ${name} IN ('朋友圈','朋友屠','用友殿','田友殿') THEN '朋友圈'
      WHEN ${name} IN ('微信','微佳') OR ${name} ~ '^微信[（(][0-9]+[）)]$' THEN '微信'
      WHEN ${name} IN ('发现','发机') THEN '发现' ELSE ${name} END
    ELSE ${name} END)`;
}
/** 可读但不完整的标题只按独立来源展示，不能按可见简称扩大查询/删除范围。 */
export function truncatedConversation(alias = 'c'): string {
  return `${alias}.external_key ~ '^screenshot-v2:truncated:[a-f0-9-]{36}$'`;
}
/** 占位名称本身也是未确认标志；不能因旧数据的confidence偏高而漏掉随机后缀标签。 */
export function pendingConversation(alias = 'c'): string {
  return `${alias}.merged_into_id IS NULL AND NOT (${truncatedConversation(alias)}) AND (
    btrim(COALESCE(${alias}.display_name,'')) LIKE '待确认%'
    OR (${alias}.identity_confidence < 0.8 AND NOT (${alias}.platform='wechat' AND ${alias}.account_key='wechat-empty-tree' AND COALESCE(${conversationGroupName(alias)},'') IN ('朋友圈','微信','发现')) AND (
      ${alias}.external_key LIKE 'screenshot-v2:%' OR ${alias}.external_key LIKE 'capture-v3:%'
      OR ${alias}.external_key LIKE 'screenshot-pending:%' OR ${alias}.external_key LIKE 'capture-pending:%'
      OR ${alias}.external_key LIKE 'notification-v2:%' OR ${alias}.external_key LIKE 'header:%')))`;
}
export function pendingScope(id: number, platform: unknown): platform is string {
  return id === -1 && typeof platform === 'string' && chatPlatforms.includes(platform);
}
export function pendingMessageScope(userParam: string, platformParam: string): string {
  return `conversation_id IN (SELECT c.id FROM chat_conversation c WHERE c.user_id=${userParam}
    AND c.platform=${platformParam} AND ${pendingConversation()})`;
}

/** 读取/预览/批量图片删除共用同一范围；组名只是展示范围，不改写真实来源。 */
export function chatConversationScope(userId: string, id: number, platform: unknown, name: unknown) {
  if (!Number.isSafeInteger(id)) return null;
  if (name !== undefined) {
    if (id <= 0 || typeof name !== 'string' || !name.length || name.length > 500 ||
        typeof platform !== 'string' || !chatPlatforms.includes(platform)) return null;
    return { sql: `c.user_id=$1 AND c.platform=$3 AND c.merged_into_id IS NULL
        AND NOT (${pendingConversation()}) AND NOT (${truncatedConversation()}) AND ${conversationGroupName()}=$2`, params: [userId, name, platform], mode: 'name' as const };
  }
  if (pendingScope(id, platform)) return {
    sql: `c.user_id=$1 AND c.platform=$2 AND ${pendingConversation()}`, params: [userId, platform], mode: 'pending' as const,
  };
  return id > 0 ? { sql: 'c.user_id=$1 AND c.id=$2', params: [userId, id], mode: 'source' as const } : null;
}

export function createChatPendingRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/conversations', async (req, res, next) => {
    const groupNames = req.query.group_names === 'true';
    if (req.query.group_pending !== 'true' && !groupNames) return next();
    const platform = req.query.platform;
    if (typeof platform !== 'string' || !chatPlatforms.includes(platform)) {
      res.status(400).json({ error: '分组查询必须指定platform' }); return;
    }
    const exactName = req.query.name;
    if (exactName !== undefined && (!groupNames || typeof exactName !== 'string' || !exactName.length || exactName.length > 500)) {
      res.status(400).json({ error: '分组名称无效' }); return;
    }
    const page = Math.max(1, Math.trunc(Number(req.query.page) || 1));
    const pageSize = Math.min(100, Math.max(1, Math.trunc(Number(req.query.page_size) || 20)));
    const search = typeof req.query.q === 'string' ? req.query.q.trim().slice(0,100) : '';
    const params: unknown[] = [res.locals.userId, platform, `%${search.replace(/[\\%_]/g, '\\$&')}%`];
    if (exactName !== undefined) params.push(exactName);
    try {
      const scope = `c.user_id=$1 AND c.platform=$2 AND c.merged_into_id IS NULL`;
      const pending = pendingConversation();
      const known = `${scope} AND NOT (${pending}) AND COALESCE(${groupNames ? conversationGroupName() : 'c.display_name'},c.external_key) ILIKE $3${exactName === undefined ? '' : ` AND NOT (${truncatedConversation()}) AND ${conversationGroupName()}=$4`}`;
      // 空名称保留各自来源；不能把所有缺名称记录误当同一个已知联系人。
      const groupingKey = groupNames ? `CASE WHEN ${truncatedConversation()} THEN 'id:' || c.id::text ELSE COALESCE('name:' || NULLIF(${conversationGroupName()},''), 'id:' || c.id::text) END` : 'c.id::text';
      const [summary, count] = await Promise.all([
        pool.query(`SELECT COUNT(DISTINCT c.id) AS sources,COUNT(m.id) AS message_count,
          MIN(c.first_seen_at) AS first_seen_at,MAX(c.last_seen_at) AS last_seen_at,MAX(m.captured_at) AS last_message_at
          FROM chat_conversation c LEFT JOIN chat_message m ON m.conversation_id=c.id AND m.user_id=c.user_id AND ${visibleChatMessage()}
          WHERE ${scope} AND ${pending}`, params.slice(0,2)),
        pool.query(`SELECT COUNT(DISTINCT ${groupingKey}) AS count FROM chat_conversation c WHERE ${known}`, params),
      ]);
      const bucket = !search && exactName === undefined && Number(summary.rows[0].message_count) > 0 ? 1 : 0;
      const offset = Math.max(0, (page-1)*pageSize-bucket);
      const limit = pageSize - (page === 1 ? bucket : 0);
      const result = await pool.query(`WITH sources AS (
        SELECT c.*, ${groupingKey} AS grouping_key, COUNT(m.id) AS message_count, MAX(m.captured_at) AS last_message_at
        FROM chat_conversation c LEFT JOIN chat_message m ON m.conversation_id=c.id AND m.user_id=c.user_id AND ${visibleChatMessage()}
        WHERE ${known} GROUP BY c.id
      ), groups AS (
        SELECT MIN(id) AS id,ARRAY_AGG(id ORDER BY id) AS source_ids,COUNT(*) AS source_count,
          SUM(message_count) AS message_count,MAX(last_message_at) AS last_message_at,
          MIN(first_seen_at) AS first_seen_at,MAX(last_seen_at) AS last_seen_at
        FROM sources GROUP BY grouping_key
      ) SELECT c.*,
        ${groupNames ? `CASE WHEN ${truncatedConversation()} THEN NULL ELSE NULLIF(${conversationGroupName()},'') END` : 'NULL::text'} AS group_name,
        ${groupNames ? `COALESCE(NULLIF(${conversationGroupName()},''),c.display_name)` : 'c.display_name'} AS display_name,g.*
        FROM groups g JOIN chat_conversation c ON c.id=g.id
        ORDER BY g.last_seen_at DESC,g.id DESC LIMIT $${params.length+1} OFFSET $${params.length+2}`, [...params,limit,offset]);
      const conversations = result.rows.map(row=>({...row,id:Number(row.id),identity_confidence:Number(row.identity_confidence),message_count:Number(row.message_count),
        source_count:Number(row.source_count),source_ids:row.source_ids.map(Number),is_name_group:groupNames && row.group_name !== null}));
      if (page === 1 && bucket) conversations.unshift({ ...summary.rows[0], id:-1, platform, account_key:'',external_key:'pending-collection',
        display_name:'待确认会话',conversation_type:'unknown',identity_confidence:0,message_count:Number(summary.rows[0].message_count),is_pending_group:true });
      res.json({total:Number(count.rows[0].count)+bucket,page,page_size:pageSize,conversations});
    } catch (error) { next(error); }
  });
  return router;
}
