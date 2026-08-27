-- 系统图片表达素材、基础 Emoji、有序组合和用户使用计数
CREATE TABLE IF NOT EXISTS expression_asset (
    id                  TEXT PRIMARY KEY,
    type                VARCHAR(30) NOT NULL CHECK (type IN ('prebuilt', 'synthesis-template')),
    format              VARCHAR(10) NOT NULL CHECK (format IN ('gif', 'png', 'jpg', 'jpeg', 'webp')),
    version             TEXT NOT NULL,
    file_name           TEXT NOT NULL UNIQUE,
    thumbnail_file_name TEXT,
    sha256              CHAR(64) NOT NULL,
    width               INT NOT NULL CHECK (width > 0),
    height              INT NOT NULL CHECK (height > 0),
    keywords            TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    emotions            TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    embedded_text       TEXT,
    text_safe_area      JSONB,
    layout              JSONB,
    heat                BIGINT NOT NULL DEFAULT 0 CHECK (heat >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_expression_asset_keywords
    ON expression_asset USING GIN (keywords);
CREATE INDEX IF NOT EXISTS idx_expression_asset_emotions
    ON expression_asset USING GIN (emotions);
CREATE INDEX IF NOT EXISTS idx_expression_asset_heat
    ON expression_asset (heat DESC);

CREATE TABLE IF NOT EXISTS emoji_base (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    emotions   TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    file_name  TEXT NOT NULL UNIQUE,
    sha256     CHAR(64) NOT NULL,
    version    TEXT NOT NULL,
    width      INT NOT NULL DEFAULT 256 CHECK (width > 0),
    height     INT NOT NULL DEFAULT 256 CHECK (height > 0),
    sort_order INT NOT NULL UNIQUE CHECK (sort_order >= 0)
);

CREATE TABLE IF NOT EXISTS emoji_combination (
    first_id  TEXT NOT NULL REFERENCES emoji_base(id) ON DELETE CASCADE,
    second_id TEXT NOT NULL REFERENCES emoji_base(id) ON DELETE CASCADE,
    file_name TEXT NOT NULL UNIQUE,
    sha256    CHAR(64) NOT NULL,
    version   TEXT NOT NULL,
    width     INT NOT NULL DEFAULT 256 CHECK (width > 0),
    height    INT NOT NULL DEFAULT 256 CHECK (height > 0),
    heat      BIGINT NOT NULL DEFAULT 0 CHECK (heat >= 0),
    PRIMARY KEY (first_id, second_id)
);

CREATE INDEX IF NOT EXISTS idx_emoji_combination_heat
    ON emoji_combination (heat DESC);

CREATE TABLE IF NOT EXISTS expression_asset_usage (
    user_id      UUID NOT NULL,
    asset_id     TEXT NOT NULL REFERENCES expression_asset(id) ON DELETE CASCADE,
    use_count    BIGINT NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, asset_id)
);

CREATE INDEX IF NOT EXISTS idx_expression_asset_usage_user_count
    ON expression_asset_usage (user_id, use_count DESC);
