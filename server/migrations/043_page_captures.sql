-- 微信/抖音非聊天页面独立归档；图片与元信息同一行，不生成联系人或聊天消息。
CREATE TABLE IF NOT EXISTS page_capture (
  user_id UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  id UUID NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('wechat', 'douyin')),
  kind TEXT NOT NULL CHECK (kind IN ('conversation_list', 'payment', 'media_feed', 'image_post', 'mini_app')),
  captured_at TIMESTAMPTZ NOT NULL,
  width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 8192),
  height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 8192),
  sha256 TEXT NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
  payload_sha256 TEXT NOT NULL CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
  mime_type TEXT NOT NULL CHECK (mime_type IN ('image/png', 'image/webp')),
  screenshot BYTEA NOT NULL CHECK (octet_length(screenshot) BETWEEN 1 AND 3145728),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS page_capture_device_time ON page_capture(user_id, captured_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS page_capture_device_platform_time ON page_capture(user_id, platform, captured_at DESC, id DESC);
