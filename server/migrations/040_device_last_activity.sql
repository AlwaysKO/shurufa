-- 补齐旧上报入口漏记的设备活跃：仅使用服务器入库时间，不用执行迁移的现在。
-- 位置表first/last_seen已混用手机发生时间，不纳入；持久位置回执提供可靠接收时间。
WITH reports AS (
  SELECT user_id AS device_id, MAX(created_at) AS received_at FROM input_event GROUP BY user_id
  UNION ALL SELECT user_id, MAX(received_at) FROM app_usage_segment GROUP BY user_id
  UNION ALL SELECT user_id, MAX(created_at) FROM chat_message GROUP BY user_id
  UNION ALL SELECT user_id, MAX(created_at) FROM media_asset GROUP BY user_id
  UNION ALL SELECT user_id, MAX(received_at) FROM navigation_record GROUP BY user_id
  UNION ALL SELECT user_id, MAX(received_at) FROM mobile_report_receipt GROUP BY user_id
  UNION ALL SELECT device_id, MAX(stored_at) FROM call_recording GROUP BY device_id
  UNION ALL SELECT device_id, MAX(stored_at) FROM phone_call_log GROUP BY device_id
  UNION ALL SELECT device_id, synced_at FROM phone_call_log_sync WHERE synced_at IS NOT NULL
), latest AS (
  SELECT device_id, MAX(received_at) AS received_at FROM reports GROUP BY device_id
)
UPDATE device d SET last_seen_at = latest.received_at
FROM latest WHERE d.id = latest.device_id AND d.last_seen_at < latest.received_at;
