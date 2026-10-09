-- 视频前台访问与页面图片分离；不把墙钟时间差冒充单调前台时长。
CREATE TABLE IF NOT EXISTS video_visit (
  user_id UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  id UUID NOT NULL,
  platform TEXT NOT NULL CHECK(platform IN ('wechat','douyin')),
  entered_at BIGINT NOT NULL CHECK(entered_at >= 946684800000),
  ended_at BIGINT CHECK(ended_at >= 946684800000),
  duration_ms BIGINT CHECK(duration_ms BETWEEN 0 AND 9007199254740991),
  exit_reason TEXT NOT NULL CHECK(exit_reason IN ('switched','exit','background','locked','interrupted')),
  complete BOOLEAN NOT NULL,
  first_image_id UUID,
  last_image_id UUID,
  payload_sha256 TEXT NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id,id),
  FOREIGN KEY(user_id,first_image_id) REFERENCES page_capture(user_id,id),
  FOREIGN KEY(user_id,last_image_id) REFERENCES page_capture(user_id,id),
  CHECK((exit_reason='interrupted' AND NOT complete AND ended_at IS NULL AND duration_ms IS NULL)
    OR (exit_reason<>'interrupted' AND ended_at IS NOT NULL AND
      ((complete AND duration_ms IS NOT NULL) OR (NOT complete AND duration_ms IS NULL))))
);
CREATE INDEX IF NOT EXISTS video_visit_device_time ON video_visit(user_id,entered_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS video_visit_device_platform_time ON video_visit(user_id,platform,entered_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_page_capture_user_received ON page_capture(user_id,received_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_visit_user_received ON video_visit(user_id,received_at DESC);
