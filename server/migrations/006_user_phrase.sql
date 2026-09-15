-- 用户常用语库（云端同步）
CREATE TABLE IF NOT EXISTS user_phrase (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID NOT NULL,
    content    TEXT NOT NULL UNIQUE,       -- 常用语内容（唯一，同步幂等）
    sort_order INT  NOT NULL DEFAULT 0,    -- 排序权重（越大越靠前）
    use_count  BIGINT NOT NULL DEFAULT 0,  -- 使用次数
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_phrase_user_id ON user_phrase(user_id);
