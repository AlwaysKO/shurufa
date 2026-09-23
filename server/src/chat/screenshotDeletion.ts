import type pg from 'pg';
type Image = { message_id: string; asset_id: number };

/** 已验证的可见选图范围内，连带移除同次截图副本的同一附件；收据保留供上传幂等。 */
export async function expandScreenshotDeletion(client: pg.PoolClient, userId: string, images: Image[]) {
  const rootIds = [...new Set(images.map(image => image.message_id))];
  const placeholders = rootIds.map((_, i) => `$${i + 2}`).join(',');
  const duplicates = await client.query(`SELECT id FROM chat_message WHERE user_id=$1
    AND metadata->>'screenshot_duplicate_of' IN (${placeholders}) LIMIT 1`, [userId, ...rootIds]);
  if (!duplicates.rowCount) return { images, receiptIds: [] as string[] };
  const family = await client.query<{ root_id: string; id: string; asset_id: string }>(`WITH RECURSIVE family AS (
    SELECT selected.message_id AS root_id,m.id,m.user_id,m.conversation_id,m.device_id,m.platform,selected.asset_id
    FROM jsonb_to_recordset($2::jsonb) selected(message_id uuid,asset_id bigint)
    JOIN chat_message m ON m.id=selected.message_id AND m.user_id=$1
    UNION
    SELECT f.root_id,m.id,m.user_id,m.conversation_id,m.device_id,m.platform,f.asset_id
    FROM family f JOIN chat_message m ON m.metadata->>'screenshot_duplicate_of'=f.id::text
      AND m.user_id=f.user_id AND m.conversation_id=f.conversation_id AND m.device_id=f.device_id AND m.platform=f.platform
  ) SELECT root_id,id,asset_id FROM family`, [userId, JSON.stringify(images)]);
  const duplicateRoots = new Set(family.rows.filter(row => row.id !== row.root_id).map(row => row.root_id));
  return {
    images: family.rows.map(row => ({ message_id: row.id, asset_id: Number(row.asset_id) })),
    receiptIds: [...new Set(family.rows.filter(row => duplicateRoots.has(row.root_id)).map(row => row.id))],
  };
}

export async function tombstoneDeletedScreenshots(client: pg.PoolClient, userId: string, receiptIds: string[]) {
  if (!receiptIds.length) return;
  // 部分删除也不能让旧批次再次索要已删附件；未选中的图片继续正常展示。
  await client.query(`UPDATE chat_message SET metadata=metadata || '{"screenshot_assets_deleted":true}'::jsonb
    WHERE user_id=$1 AND id=ANY($2::uuid[])`, [userId, receiptIds]);
  await client.query(`UPDATE chat_message m SET metadata=metadata || '{"screenshot_deleted":true}'::jsonb
    WHERE m.user_id=$1 AND m.id=ANY($2::uuid[]) AND m.message_type='image'
      AND COALESCE(m.text,'') IN ('','图片','截图','聊天截图')
      AND NOT EXISTS(SELECT 1 FROM chat_message_asset ma WHERE ma.message_id=m.id)`, [userId, receiptIds]);
}
