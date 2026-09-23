-- 同次截图归属查找在上报事务中执行，避免持有会话锁时扫描全部历史图片。
CREATE INDEX IF NOT EXISTS idx_chat_message_screenshot_captured
    ON chat_message(user_id, device_id, platform, captured_at)
    WHERE message_type = 'image';
CREATE INDEX IF NOT EXISTS idx_chat_message_screenshot_occurred
    ON chat_message(user_id, device_id, platform, occurred_at)
    WHERE message_type = 'image';
CREATE INDEX IF NOT EXISTS idx_chat_message_screenshot_capture_id
    ON chat_message(user_id, device_id, platform, (metadata->>'screenshot_capture_id'))
    WHERE message_type = 'image';
