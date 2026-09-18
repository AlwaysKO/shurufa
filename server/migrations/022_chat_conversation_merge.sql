-- 保留源会话作为永久归属映射；只有用户明确删除整个目标时才级联清理映射。
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS merged_into_id BIGINT REFERENCES chat_conversation(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_chat_conversation_merge ON chat_conversation(merged_into_id);
