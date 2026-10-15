-- 原始使用证据与原始交互分离；不从旧使用段/服务器联系回填。
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_app_activity_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_app_activity_source TEXT;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_app_activity_received_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_screen_off_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_screen_off_source TEXT;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_screen_off_received_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_lock_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_lock_source TEXT;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_lock_received_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS latest_usage_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS latest_usage_source TEXT;
CREATE INDEX IF NOT EXISTS device_latest_usage_idx ON device(latest_usage_at DESC NULLS LAST,id);
