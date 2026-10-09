-- 管理元信息不改变移动端载荷摘要；删除后只保留验证重试必需的回执，不保留图片或观看正文。
ALTER TABLE page_capture ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT '' CHECK(length(title)<=200);
ALTER TABLE page_capture ADD COLUMN IF NOT EXISTS note TEXT NOT NULL DEFAULT '' CHECK(length(note)<=2000);
ALTER TABLE video_visit ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT '' CHECK(length(title)<=200);
ALTER TABLE video_visit ADD COLUMN IF NOT EXISTS note TEXT NOT NULL DEFAULT '' CHECK(length(note)<=2000);
CREATE TABLE IF NOT EXISTS capture_tombstone (
  user_id UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  record_type TEXT NOT NULL CHECK(record_type IN ('page','video')),
  id UUID NOT NULL,
  platform TEXT NOT NULL CHECK(platform IN ('wechat','douyin')),
  payload_sha256 TEXT NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
  sha256 TEXT CHECK(sha256 ~ '^[a-f0-9]{64}$'),
  deleted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id,record_type,id)
);
CREATE INDEX IF NOT EXISTS video_visit_first_image ON video_visit(user_id,first_image_id) WHERE first_image_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS video_visit_last_image ON video_visit(user_id,last_image_id) WHERE last_image_id IS NOT NULL;
