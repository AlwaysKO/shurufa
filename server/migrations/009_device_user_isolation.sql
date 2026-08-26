-- 一台设备对应一个用户空间；常用语内容只需在同一设备内唯一。
ALTER TABLE user_phrase DROP CONSTRAINT IF EXISTS user_phrase_content_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_phrase_user_content
    ON user_phrase(user_id, content);

-- 旧版允许设备注册失败后继续写事件；补建占位设备，确保后台能选择这些用户空间。
INSERT INTO device (id, name, platform)
SELECT DISTINCT historical.device_id,
       '历史设备 ' || LEFT(historical.device_id::text, 8),
       'android'
FROM (
    SELECT device_id FROM input_event
    UNION SELECT device_id FROM location_track
    UNION SELECT device_id FROM input_session
    UNION SELECT device_id FROM chat_message
) historical
WHERE historical.device_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

-- 旧版固定用户空间保留为明确的“旧版共享数据”，不猜测归给任意真实手机。
INSERT INTO device (id, name, platform)
SELECT '00000000-0000-0000-0000-000000000001', '旧版共享数据（待归属）', 'legacy'
WHERE EXISTS (
    SELECT 1 FROM sticker WHERE user_id = '00000000-0000-0000-0000-000000000001'
    UNION ALL SELECT 1 FROM user_phrase WHERE user_id = '00000000-0000-0000-0000-000000000001'
    UNION ALL SELECT 1 FROM chat_conversation WHERE user_id = '00000000-0000-0000-0000-000000000001'
    UNION ALL SELECT 1 FROM media_asset WHERE user_id = '00000000-0000-0000-0000-000000000001'
)
ON CONFLICT (id) DO NOTHING;

-- 有明确 device_id 的历史数据可无损归位；仅处理旧版固定用户，重复执行不会改动新数据。
UPDATE input_event SET user_id = device_id
WHERE user_id = '00000000-0000-0000-0000-000000000001';
UPDATE location_track SET user_id = device_id
WHERE user_id = '00000000-0000-0000-0000-000000000001';

-- 派生统计按各设备事件重新生成，防止旧共享统计与新游标重复累计。
DELETE FROM phrase_stat WHERE user_id = '00000000-0000-0000-0000-000000000001';
DELETE FROM completion_candidate WHERE user_id = '00000000-0000-0000-0000-000000000001';
DELETE FROM analysis_state
WHERE key IN ('last_analyzed_epoch_ms', 'last_analyzed_epoch_ms:00000000-0000-0000-0000-000000000001');

-- 按消息的 device_id 为每台手机复制会话，并重绑消息；多手机历史会话不再共用同一 conversation。
CREATE TEMP TABLE IF NOT EXISTS migration_chat_conversation_map AS
SELECT DISTINCT c.id AS source_id, m.device_id, c.platform, c.account_key, c.external_key
FROM chat_conversation c
JOIN chat_message m ON m.conversation_id = c.id;

INSERT INTO chat_conversation
    (user_id, platform, account_key, external_key, display_name, conversation_type,
     identity_confidence, metadata, first_seen_at, last_seen_at)
SELECT map.device_id, source.platform, source.account_key, source.external_key,
       source.display_name, source.conversation_type, source.identity_confidence,
       source.metadata, source.first_seen_at, source.last_seen_at
FROM migration_chat_conversation_map map
JOIN chat_conversation source ON source.id = map.source_id
ON CONFLICT (user_id, platform, account_key, external_key) DO UPDATE SET
    display_name = COALESCE(EXCLUDED.display_name, chat_conversation.display_name),
    last_seen_at = GREATEST(chat_conversation.last_seen_at, EXCLUDED.last_seen_at);

ALTER TABLE migration_chat_conversation_map ADD COLUMN IF NOT EXISTS target_id BIGINT;
UPDATE migration_chat_conversation_map map
SET target_id = target.id
FROM chat_conversation target
WHERE target.user_id = map.device_id
  AND target.platform = map.platform
  AND target.account_key = map.account_key
  AND target.external_key = map.external_key;

INSERT INTO relationship_profile
    (user_id, conversation_id, relationship_type, alias, intimacy_level, humor_level,
     notes, created_at, updated_at)
SELECT map.device_id, map.target_id, profile.relationship_type, profile.alias,
       profile.intimacy_level, profile.humor_level, profile.notes,
       profile.created_at, profile.updated_at
FROM migration_chat_conversation_map map
JOIN relationship_profile profile ON profile.conversation_id = map.source_id
WHERE map.target_id IS NOT NULL
ON CONFLICT (user_id, conversation_id) DO NOTHING;

UPDATE chat_message message
SET user_id = map.device_id, conversation_id = map.target_id
FROM migration_chat_conversation_map map
WHERE message.conversation_id = map.source_id
  AND message.device_id = map.device_id
  AND map.target_id IS NOT NULL
  AND (message.user_id <> map.device_id OR message.conversation_id <> map.target_id);

-- 同一旧媒体被多台手机消息引用时，为每台手机建立独立数据库记录并重绑关联。
INSERT INTO media_asset
    (user_id, sha256, perceptual_hash, mime_type, storage_path, byte_size,
     width, height, created_at)
SELECT DISTINCT message.device_id, source.sha256, source.perceptual_hash, source.mime_type,
       source.storage_path, source.byte_size, source.width, source.height, source.created_at
FROM chat_message_asset link
JOIN chat_message message ON message.id = link.message_id
JOIN media_asset source ON source.id = link.asset_id
ON CONFLICT (user_id, sha256) DO NOTHING;

UPDATE chat_message_asset link
SET asset_id = target.id
FROM chat_message message, media_asset source, media_asset target
WHERE message.id = link.message_id
  AND source.id = link.asset_id
  AND target.user_id = message.device_id
  AND target.sha256 = source.sha256
  AND link.asset_id <> target.id;

UPDATE relationship_profile profile
SET user_id = conversation.user_id
FROM chat_conversation conversation
WHERE profile.conversation_id = conversation.id
  AND profile.user_id <> conversation.user_id;

DELETE FROM chat_conversation conversation
WHERE conversation.user_id = '00000000-0000-0000-0000-000000000001'
  AND NOT EXISTS (SELECT 1 FROM chat_message WHERE conversation_id = conversation.id)
  AND EXISTS (SELECT 1 FROM migration_chat_conversation_map WHERE source_id = conversation.id);

DROP TABLE migration_chat_conversation_map;
