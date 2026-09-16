-- 按内容保留删除标记；不关联可重建的目录ID，避免重新补录后复活。
CREATE TABLE IF NOT EXISTS keyword_gif_removal (
  user_id UUID NOT NULL,
  sha256 TEXT NOT NULL,
  asset_id TEXT NOT NULL,
  removed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, sha256)
);
