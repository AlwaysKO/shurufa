-- 设备目录只读取当前页设备每类业务的最新服务器接收时间，不扫描完整历史。
CREATE INDEX IF NOT EXISTS idx_input_event_user_created ON input_event(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_usage_user_received ON app_usage_segment(user_id, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_message_user_created ON chat_message(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_asset_user_created ON media_asset(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_navigation_user_received ON navigation_record(user_id, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_mobile_receipt_user_received ON mobile_report_receipt(user_id, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_call_recording_device_stored ON call_recording(device_id, stored_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_phone_call_device_stored ON phone_call_log(device_id, stored_at DESC);
