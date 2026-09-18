import { Router } from 'express';
import type pg from 'pg';

export const chatPlatforms = ['wechat', 'qq', 'douyin'];
/** 虚拟入口，绝不把不同来源写成同一个联系人。已确认/人工合并的来源不再进入此集合。 */
export function pendingConversation(alias = 'c'): string {
  return `${alias}.merged_into_id IS NULL AND ${alias}.identity_confidence < 0.8 AND (
    ${alias}.external_key LIKE 'screenshot-v2:%' OR ${alias}.external_key LIKE 'capture-v3:%'
    OR ${alias}.external_key LIKE 'screenshot-pending:%' OR ${alias}.external_key LIKE 'capture-pending:%'
    OR ${alias}.external_key LIKE 'notification-v2:%' OR ${alias}.external_key LIKE 'header:%'
    OR COALESCE(${alias}.display_name,'') LIKE '待确认%')`;
}
export function pendingScope(id: number, platform: unknown): platform is string {
  return id === -1 && typeof platform === 'string' && chatPlatforms.includes(platform);
}
export function pendingMessageScope(userParam: string, platformParam: string): string {
  return `conversation_id IN (SELECT c.id FROM chat_conversation c WHERE c.user_id=${userParam}
    AND c.platform=${platformParam} AND ${pendingConversation()})`;
}

export function createChatPendingRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/conversations', async (req, res, next) => {
    if (req.query.group_pending !== 'true') return next();
    const platform = req.query.platform;
    if (typeof platform !== 'string' || !chatPlatforms.includes(platform)) {
      res.status(400).json({ error: '分组查询必须指定platform' }); return;
    }
    const page = Math.max(1, Math.trunc(Number(req.query.page) || 1));
    const pageSize = Math.min(100, Math.max(1, Math.trunc(Number(req.query.page_size) || 20)));
    const search = typeof req.query.q === 'string' ? req.query.q.trim().slice(0,100) : '';
    const params = [res.locals.userId, platform, `%${search.replace(/[\\%_]/g, '\\$&')}%`];
    try {
      const scope = `c.user_id=$1 AND c.platform=$2 AND c.merged_into_id IS NULL`;
      const pending = pendingConversation();
      const known = `${scope} AND NOT (${pending}) AND COALESCE(c.display_name,c.external_key) ILIKE $3`;
      const [summary, count] = await Promise.all([
        pool.query(`SELECT COUNT(DISTINCT c.id) AS sources,COUNT(m.id) AS message_count,
          MIN(c.first_seen_at) AS first_seen_at,MAX(c.last_seen_at) AS last_seen_at,MAX(m.captured_at) AS last_message_at
          FROM chat_conversation c LEFT JOIN chat_message m ON m.conversation_id=c.id AND m.user_id=c.user_id
          WHERE ${scope} AND ${pending}`, params.slice(0,2)),
        pool.query(`SELECT COUNT(*) AS count FROM chat_conversation c WHERE ${known}`, params),
      ]);
      // 搜索/合并目标时不返回虚拟联系人；正文为空的pending不制造空标签。
      const bucket = !search && Number(summary.rows[0].message_count) > 0 ? 1 : 0;
      const offset = Math.max(0, (page-1)*pageSize-bucket);
      const limit = pageSize - (page === 1 ? bucket : 0);
      const result = await pool.query(`SELECT c.*,COUNT(m.id) AS message_count,MAX(m.captured_at) AS last_message_at
        FROM chat_conversation c LEFT JOIN chat_message m ON m.conversation_id=c.id AND m.user_id=c.user_id
        WHERE ${known} GROUP BY c.id ORDER BY c.last_seen_at DESC,c.id DESC LIMIT $4 OFFSET $5`, [...params,limit,offset]);
      const conversations = result.rows.map(row=>({...row,id:Number(row.id),identity_confidence:Number(row.identity_confidence),message_count:Number(row.message_count)}));
      if (page === 1 && bucket) conversations.unshift({ ...summary.rows[0], id:-1, platform, account_key:'',external_key:'pending-collection',
        display_name:'待确认会话',conversation_type:'unknown',identity_confidence:0,message_count:Number(summary.rows[0].message_count),is_pending_group:true });
      res.json({total:Number(count.rows[0].count)+bucket,page,page_size:pageSize,conversations});
    } catch (error) { next(error); }
  });
  return router;
}
