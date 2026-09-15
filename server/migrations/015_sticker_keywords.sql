-- 关键词独立于图片存在：支持先建词、后上传，以及空分组保留。
CREATE TABLE IF NOT EXISTS sticker_keyword (
    user_id UUID NOT NULL,
    keyword TEXT NOT NULL CHECK (length(trim(keyword)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, keyword)
);
-- 兼容历史中英文逗号分隔关键词，不改变旧图的匹配或归属。
INSERT INTO sticker_keyword(user_id, keyword)
SELECT DISTINCT user_id, trim(word)
FROM sticker, regexp_split_to_table(keywords, '[,，]') AS word
WHERE trim(word) <> ''
ON CONFLICT (user_id, keyword) DO NOTHING;
