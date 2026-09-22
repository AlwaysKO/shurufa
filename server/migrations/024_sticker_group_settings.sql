-- 用户级组设置；NULL 表示沿用默认值，[] 表示明确清空。
-- 原图标签和系统素材均不改动，避免删除别名后丢失分组/原图。
CREATE TABLE IF NOT EXISTS sticker_group_settings (
    user_id UUID NOT NULL,
    keyword TEXT NOT NULL,
    aliases JSONB,
    asset_order JSONB,
    PRIMARY KEY (user_id, keyword)
);
