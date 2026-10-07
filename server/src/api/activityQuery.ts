/** 事件内容类型：语音 / 图片 / 文字（用于列表展示与筛选） */
export const CONTENT_TYPE_SQL = `CASE
  WHEN event_type = 'voice' THEN 'voice'
  WHEN (metadata->>'media_type') IN ('image','picture')
   OR metadata ? 'image_uri' OR metadata ? 'image_url'
   OR (event_type IN ('paste','paste_inferred','clipboard_change') AND (text IS NULL OR text = '')) THEN 'image'
  ELSE 'text'
END`;

/** 行为事件（列表默认展示：输入/粘贴/复制/语音等有内容的行为，不含 key/compose 等底层事件） */
export const BEHAVIOR_TYPES = "('commit','candidate_commit','paste','paste_inferred','external_insert','clipboard_change','voice','delete','external_delete')";

/** 列表与跨页清理使用同一筛选口径。 */
export function activityConditions(userId: string, query: Record<string, unknown>) {
  const deviceId = (query.device_id as string) ?? null;
  const packageName = (query.package_name as string) ?? null;
  const q = ((query.q as string) ?? '').trim();
  const type = (query.type as string) ?? 'all';
  const from = (query.from as string) ?? null;
  const to = (query.to as string) ?? null;
  const days = query.days ? Math.min(Math.max(Number(query.days) || 0, 1), 3650) : null;
  const showAll = query.all === '1';
  const conds: string[] = ['user_id = $1'];
  const params: unknown[] = [userId];
  let n = 1;
  const add = (cond: string, ...vs: unknown[]) => {
    let sql = cond;
    for (const v of vs) {
      n++;
      sql = sql.replace('?', `$${n}`);
      params.push(v);
    }
    conds.push(sql);
  };

  if (deviceId) add('device_id = ?', deviceId);
  if (packageName) add('package_name = ?', packageName);
  if (from) add('occurred_at >= ?', new Date(from));
  if (to) add('occurred_at <= ?', new Date(to));
  if (days) add('occurred_at >= ?', new Date(Date.now() - days * 86_400_000));
  if (q) add('(text ILIKE ? OR text_before ILIKE ? OR text_after ILIKE ? OR input_code ILIKE ? OR client_ip = ?)', `%${q}%`, `%${q}%`, `%${q}%`, `%${q}%`, q);
  if (!showAll) conds.push(`event_type IN ${BEHAVIOR_TYPES}`);

  // 类型筛选（对应列表“类型”列）
  const typeConds: Record<string, string> = {
    text: `event_type IN ('commit','candidate_commit','external_insert')`,
    delete: `event_type IN ('delete','external_delete')`,
    paste: `event_type IN ('paste','paste_inferred','clipboard_change')`,
    voice: `event_type = 'voice'`,
    image: `${CONTENT_TYPE_SQL} = 'image'`,
  };
  if (typeConds[type]) conds.push(typeConds[type]);

  const where = conds.join(' AND ');

  return { where, params };
}
