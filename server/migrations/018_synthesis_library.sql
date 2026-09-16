-- AI 合成底图与关键词推荐完全分表；唯一索引是并发去重的最终边界。
ALTER TABLE sticker ADD COLUMN IF NOT EXISTS sha256 TEXT;
CREATE TABLE IF NOT EXISTS synthesis_asset (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL,
 name TEXT NOT NULL,
 file_name TEXT NOT NULL UNIQUE,
 sha256 TEXT NOT NULL,
 width INTEGER NOT NULL,
 height INTEGER NOT NULL,
 text_safe_area JSONB NOT NULL,
 layout JSONB NOT NULL,
 source_statement TEXT NOT NULL,
 no_text_confirmed BOOLEAN NOT NULL,
 rights_confirmed BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE (user_id, sha256)
);
