-- 行为明细：事件记录请求 IP 与懒解析的地理位置
ALTER TABLE input_event ADD COLUMN IF NOT EXISTS client_ip TEXT;
ALTER TABLE input_event ADD COLUMN IF NOT EXISTS ip_location TEXT;

CREATE INDEX IF NOT EXISTS idx_event_ip ON input_event(client_ip);
