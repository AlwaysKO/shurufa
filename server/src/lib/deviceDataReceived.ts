import type pg from 'pg';

/** 现存业务记录/持久回执的服务器时间；联系时间和手机事件时间不构成入库证据。 */
export async function deviceDataReceivedAt(pool: pg.Pool, ids: string[]): Promise<Map<string, Date | null>> {
  if (!ids.length) return new Map();
  // 每个来源只读取当前页设备索引中的第一条，避免聚合全库历史记录。
  const result = await pool.query<{ id: string; last_data_received_at: Date | null }>(`
    SELECT selected.id, GREATEST(
      (SELECT created_at FROM input_event WHERE user_id=selected.id ORDER BY created_at DESC LIMIT 1),
      (SELECT received_at FROM app_usage_segment WHERE user_id=selected.id ORDER BY received_at DESC LIMIT 1),
      (SELECT created_at FROM chat_message WHERE user_id=selected.id ORDER BY created_at DESC LIMIT 1),
      (SELECT created_at FROM media_asset WHERE user_id=selected.id ORDER BY created_at DESC LIMIT 1),
      (SELECT received_at FROM navigation_record WHERE user_id=selected.id ORDER BY received_at DESC LIMIT 1),
      (SELECT received_at FROM mobile_report_receipt WHERE user_id=selected.id ORDER BY received_at DESC LIMIT 1),
      (SELECT stored_at FROM call_recording WHERE device_id=selected.id AND deleted_at IS NULL ORDER BY stored_at DESC LIMIT 1),
      (SELECT stored_at FROM phone_call_log WHERE device_id=selected.id ORDER BY stored_at DESC LIMIT 1)
    ) AS last_data_received_at
    FROM unnest($1::uuid[]) AS selected(id)`, [ids]);
  return new Map(result.rows.map(row => [row.id, row.last_data_received_at]));
}
