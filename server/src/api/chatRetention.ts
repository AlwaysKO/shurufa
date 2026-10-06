import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';
import { chatPlatforms, pendingConversation } from './chatPending.js';
import { visibleChatMessage } from '../chat/chatMessageVisibility.js';
import { cleanDeviceFiles, queueUploadFileCleanup } from '../lib/deleteDeviceData.js';
import { expandScreenshotDeletion, tombstoneDeletedScreenshots } from '../chat/screenshotDeletion.js';

const TTL = 15 * 60_000;
const MAX_MESSAGES = 50_000;
const BATCH_SIZE = 200;
type Target = { id: string; conversation_id: string; captured_at: Date; signature: string; asset_ids: string[]; image_ids: string[] };
type Snapshot = {
  user: string; platform: string; cutoff: Date; images: boolean; targets: Target[];
  expires: number; busy: boolean; processed: number; deleted: number; skipped: number; filesPending: boolean;
};
const selection = `SELECT m.id,m.conversation_id,m.captured_at,md5(row_to_json(m)::text) AS signature,
  ARRAY(SELECT ma.asset_id::text FROM chat_message_asset ma WHERE ma.message_id=m.id ORDER BY ma.asset_id,ma.role,ma.position) AS asset_ids,
  ARRAY(SELECT ma.asset_id::text FROM chat_message_asset ma JOIN media_asset a ON a.id=ma.asset_id
    WHERE ma.message_id=m.id AND a.mime_type LIKE 'image/%' ORDER BY ma.asset_id,ma.role,ma.position) AS image_ids
  FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id AND c.user_id=m.user_id
  WHERE m.user_id=$1 AND c.platform=$2 AND m.platform=$2 AND (${pendingConversation()})
    AND ${visibleChatMessage()} AND m.captured_at < $3
    AND ($4::boolean OR (m.message_type<>'image' AND NOT EXISTS(
      SELECT 1 FROM chat_message_asset ma JOIN media_asset a ON a.id=ma.asset_id WHERE ma.message_id=m.id AND a.mime_type LIKE 'image/%')))`;
function progress(job: Snapshot) {
  return { processed: job.processed, total: job.targets.length, deleted_messages: job.deleted,
    skipped_messages: job.skipped, done: job.processed === job.targets.length, files_pending: job.filesPending };
}

/** Short-lived frozen selections: restart/expiry requires a new preview, never a wider deletion. */
export function createChatRetentionRouter(pool: pg.Pool): Router {
  const router = Router();
  const snapshots = new Map<string, Snapshot>();
  function expire() {
    for (const [token, job] of snapshots) if (!job.busy && job.expires < Date.now()) snapshots.delete(token);
  }
  router.post('/pending/cleanup/preview', async (req, res, next) => {
    const body = req.body;
    if (![7,30].includes(body?.days) || !chatPlatforms.includes(body?.platform) || typeof body?.include_images !== 'boolean') {
      res.status(400).json({ error: '请选择保留7天或30天、来源App和是否包含图片' }); return;
    }
    expire();
    if (snapshots.size >= 20) { res.status(429).json({ error: '清理预览较多，请稍后重试' }); return; }
    const cutoff = new Date(Date.now() - body.days * 86_400_000);
    try {
      const result = await pool.query<Target>(`${selection} ORDER BY m.captured_at,m.id LIMIT $5`,
        [res.locals.userId, body.platform, cutoff, body.include_images, MAX_MESSAGES + 1]);
      if (result.rows.length > MAX_MESSAGES) { res.status(400).json({ error: '待清理记录超过5万条，请联系管理员分段处理' }); return; }
      const token = randomUUID();
      // A user may replace an unused preview; running/partially completed work remains retryable.
      for (const [key, job] of snapshots) if (job.user === res.locals.userId && !job.busy && job.processed === 0) snapshots.delete(key);
      if (result.rows.length) snapshots.set(token, { user: res.locals.userId, platform: body.platform, cutoff,
        images: body.include_images, targets: result.rows, expires: Date.now() + TTL, busy: false,
        processed: 0, deleted: 0, skipped: 0, filesPending: false });
      res.json({ token, days: body.days, include_images: body.include_images, cutoff: cutoff.toISOString(),
        total_messages: result.rows.length, total_images: result.rows.reduce((sum,row)=>sum + new Set(row.image_ids).size,0),
        first_captured_at: result.rows[0]?.captured_at ?? null, last_captured_at: result.rows.at(-1)?.captured_at ?? null });
    } catch (error) { next(error); }
  });

  router.post('/pending/cleanup/batch', async (req, res, next) => {
    const body = req.body;
    if (body?.confirm !== 'DELETE' || typeof body?.token !== 'string' || !Number.isSafeInteger(body?.offset) || body.offset < 0) {
      res.status(400).json({ error: '清理确认参数无效' }); return;
    }
    expire();
    const job = snapshots.get(body.token);
    if (!job || job.user !== res.locals.userId) { res.status(410).json({ error: '清理预览已失效，请重新预览' }); return; }
    if (job.busy || body.offset > job.processed) { res.status(409).json({ error: '清理批次正在处理或进度不一致，请重试' }); return; }
    if (body.offset < job.processed || job.processed === job.targets.length) { res.json(progress(job)); return; }
    job.busy = true;
    const targets = job.targets.slice(job.processed, job.processed + BATCH_SIZE);
    try {
      const db = await pool.connect();
      let cleanupKey: string | null = null, deleted = 0;
      try {
        await db.query('BEGIN');
        await db.query("SET LOCAL lock_timeout = '5s'");
        await db.query("SET LOCAL statement_timeout = '20s'");
        await db.query('LOCK TABLE chat_conversation, chat_message, chat_message_asset, media_asset IN SHARE ROW EXCLUSIVE MODE');
        const current = await db.query<Target>(`${selection} AND m.id=ANY($5::uuid[])`,
          [job.user, job.platform, job.cutoff, job.images, targets.map(row=>row.id)]);
        const expected = new Map(targets.map(row=>[row.id,row]));
        const matched = current.rows.filter(row=> {
          const old = expected.get(row.id)!;
          return row.signature === old.signature && row.conversation_id === old.conversation_id &&
            JSON.stringify(row.asset_ids) === JSON.stringify(old.asset_ids) && JSON.stringify(row.image_ids) === JSON.stringify(old.image_ids);
        });
        const ids = matched.map(row=>row.id), assets = [...new Set(matched.flatMap(row=>row.asset_ids))];
        const images = matched.flatMap(row=>[...new Set(row.image_ids)].map(id=>({message_id:row.id,asset_id:Number(id)})));
        const expanded = images.length ? await expandScreenshotDeletion(db,job.user,images) : {images:[],receiptIds:[]};
        // Remove all attachments of approved records, plus the same attachments on hidden duplicate receipts.
        await db.query('DELETE FROM chat_message_asset WHERE message_id=ANY($1::uuid[])',[ids]);
        if (expanded.images.length) await db.query(`DELETE FROM chat_message_asset ma USING jsonb_to_recordset($1::jsonb) selected(message_id uuid,asset_id bigint)
          WHERE ma.message_id=selected.message_id AND ma.asset_id=selected.asset_id`,[JSON.stringify(expanded.images)]);
        await tombstoneDeletedScreenshots(db,job.user,expanded.receiptIds);
        await db.query(`UPDATE chat_message SET text=NULL,sender_name=NULL,displayed_time=NULL,
          metadata='{"screenshot_deleted":true,"screenshot_assets_deleted":true}'::jsonb
          WHERE user_id=$1 AND id=ANY($2::uuid[]) AND id=ANY($3::uuid[])`,[job.user,ids,expanded.receiptIds]);
        await db.query('DELETE FROM chat_message WHERE user_id=$1 AND id=ANY($2::uuid[]) AND NOT(id=ANY($3::uuid[]))',[job.user,ids,expanded.receiptIds]);
        const removed = await db.query<{storage_path:string}>(`DELETE FROM media_asset a WHERE a.user_id=$1 AND a.id=ANY($2::bigint[])
          AND NOT EXISTS(SELECT 1 FROM chat_message_asset ma WHERE ma.asset_id=a.id) RETURNING storage_path`,[job.user,assets]);
        cleanupKey = await queueUploadFileCleanup(db,removed.rows.map(row=>row.storage_path));
        await db.query('COMMIT');
        deleted = ids.length;
      } catch (error) { await db.query('ROLLBACK').catch(()=>{}); throw error; }
      finally { db.release(); }
      job.processed += targets.length; job.deleted += deleted; job.skipped += targets.length - deleted;
      job.expires = Date.now() + TTL;
      if (cleanupKey && !await cleanDeviceFiles(pool,cleanupKey).catch(()=>false)) job.filesPending = true;
      res.json(progress(job));
    } catch (error) { next(error); }
    finally { job.busy = false; }
  });
  return router;
}
