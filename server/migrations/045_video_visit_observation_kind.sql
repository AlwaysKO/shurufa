-- 旧访问协议只接受已确认视频；保留原记录，新记录必须显式声明观察类型。
ALTER TABLE video_visit ADD COLUMN IF NOT EXISTS observation_kind TEXT NOT NULL DEFAULT 'confirmed_video';
ALTER TABLE video_visit DROP CONSTRAINT IF EXISTS video_visit_observation_kind_check;
ALTER TABLE video_visit ADD CONSTRAINT video_visit_observation_kind_check
  CHECK (observation_kind IN ('confirmed_video','unconfirmed_feed'));
ALTER TABLE video_visit DROP CONSTRAINT IF EXISTS video_visit_exit_reason_check;
ALTER TABLE video_visit ADD CONSTRAINT video_visit_exit_reason_check
  CHECK (exit_reason IN ('switched','exit','background','locked','interrupted','page_changed'));
