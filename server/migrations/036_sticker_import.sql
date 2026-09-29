-- 仅存配对码、助手令牌和租约的 SHA256；历史同 SHA 素材允许多条。
CREATE INDEX IF NOT EXISTS sticker_sha256_idx ON sticker(sha256);
CREATE TABLE IF NOT EXISTS sticker_import_pairing (
 code_hash TEXT PRIMARY KEY, expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS sticker_import_agent (
 id UUID PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), revoked_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS sticker_import_job (
 id UUID PRIMARY KEY, agent_id UUID NOT NULL REFERENCES sticker_import_agent(id),
 kind TEXT NOT NULL DEFAULT 'wechat_favorites' CHECK(kind='wechat_favorites'),
 status TEXT NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','running','completed','failed','cancelled','expired')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
 lease_hash TEXT, lease_until TIMESTAMPTZ,
 source_errors JSONB NOT NULL DEFAULT '{}',
 job_error_code TEXT CHECK(job_error_code IN ('key_not_found','snapshot_unstable','wechat_not_running','download_failed','invalid_image','collector_failed')),
 discovered INTEGER NOT NULL DEFAULT 0 CHECK(discovered BETWEEN 0 AND 10000),
 validated INTEGER NOT NULL DEFAULT 0 CHECK(validated BETWEEN 0 AND 10000),
 validation_failed INTEGER NOT NULL DEFAULT 0 CHECK(validation_failed BETWEEN 0 AND 10000)
);
CREATE UNIQUE INDEX IF NOT EXISTS sticker_import_one_active ON sticker_import_job(agent_id) WHERE status IN ('queued','running');
CREATE TABLE IF NOT EXISTS sticker_import_entry (
 job_id UUID NOT NULL REFERENCES sticker_import_job(id) ON DELETE CASCADE,
 sha256 TEXT NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
 status TEXT NOT NULL CHECK(status IN ('existing','missing','imported','failed')),
 error_code TEXT CHECK(error_code IN ('unavailable','download_failed','invalid_image','hash_mismatch','upload_failed')),
 PRIMARY KEY(job_id,sha256)
);
