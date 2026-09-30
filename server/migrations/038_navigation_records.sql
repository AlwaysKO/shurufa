-- 路线总览仅在确认开始导航后保存；截图与元数据原子写入并按设备隔离。
CREATE TABLE IF NOT EXISTS navigation_record (
  user_id UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  id UUID NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('amap', 'baidu')),
  origin TEXT NOT NULL,
  destination TEXT NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  overview_at TIMESTAMPTZ NOT NULL,
  sha256 TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL,
  mime_type TEXT NOT NULL CHECK (mime_type IN ('image/png', 'image/webp')),
  screenshot BYTEA NOT NULL CHECK (octet_length(screenshot) > 0 AND octet_length(screenshot) <= 3145728),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS navigation_record_user_started ON navigation_record(user_id, started_at DESC, id);
