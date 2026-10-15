-- 微信视频通话单帧独立分类，不混入聊天或视频号；保留既有五类页面。
ALTER TABLE page_capture DROP CONSTRAINT IF EXISTS page_capture_kind_check;
ALTER TABLE page_capture ADD CONSTRAINT page_capture_kind_check
  CHECK (kind IN ('conversation_list', 'payment', 'media_feed', 'image_post', 'mini_app', 'video_call'));
ALTER TABLE page_capture DROP CONSTRAINT IF EXISTS page_capture_video_call_platform_check;
ALTER TABLE page_capture ADD CONSTRAINT page_capture_video_call_platform_check
  CHECK (kind <> 'video_call' OR platform = 'wechat');
