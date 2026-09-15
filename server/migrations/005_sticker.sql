-- 斗图表情包：用户自建表情库，关键词搜索，使用计数
CREATE TABLE IF NOT EXISTS sticker (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID NOT NULL,
    keywords   TEXT NOT NULL,           -- 逗号分隔的关键词（如：无语,离谱,问号）
    file_name  TEXT NOT NULL,           -- 存储文件名（含扩展名，唯一）
    format     VARCHAR(10) NOT NULL,    -- gif / png / jpg / webp
    width      INT,                     -- 图片宽（px，可选）
    height     INT,                     -- 图片高（px，可选）
    use_count  BIGINT NOT NULL DEFAULT 0,  -- 发送次数（输入法选择时上报）
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sticker_file_name ON sticker(file_name);
CREATE INDEX IF NOT EXISTS idx_sticker_keywords ON sticker(keywords);
