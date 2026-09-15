-- 被动聊天采集：会话、消息与内容寻址媒体

CREATE TABLE IF NOT EXISTS chat_conversation (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    platform VARCHAR(20) NOT NULL,
    account_key TEXT NOT NULL,
    external_key TEXT NOT NULL,
    display_name TEXT,
    conversation_type VARCHAR(20) NOT NULL,
    identity_confidence NUMERIC(4,3) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, account_key, external_key)
);
CREATE INDEX IF NOT EXISTS idx_chat_conversation_last_seen
    ON chat_conversation(user_id, last_seen_at DESC);

CREATE TABLE IF NOT EXISTS media_asset (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    sha256 CHAR(64) NOT NULL,
    perceptual_hash VARCHAR(64),
    mime_type VARCHAR(100) NOT NULL,
    storage_path TEXT NOT NULL,
    byte_size BIGINT NOT NULL,
    width INT,
    height INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, sha256)
);
CREATE INDEX IF NOT EXISTS idx_media_asset_perceptual_hash
    ON media_asset(user_id, perceptual_hash);

CREATE TABLE IF NOT EXISTS chat_message (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    platform VARCHAR(20) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    sender_key TEXT NOT NULL,
    sender_name TEXT,
    direction VARCHAR(20) NOT NULL,
    message_type VARCHAR(30) NOT NULL,
    text TEXT,
    displayed_time TEXT,
    occurred_at TIMESTAMPTZ,
    captured_at TIMESTAMPTZ NOT NULL,
    sequence_hint BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_time
    ON chat_message(conversation_id, captured_at DESC);

CREATE TABLE IF NOT EXISTS chat_message_asset (
    message_id UUID NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    asset_id BIGINT NOT NULL REFERENCES media_asset(id) ON DELETE RESTRICT,
    role VARCHAR(30) NOT NULL DEFAULT 'content',
    position INT NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, asset_id, role)
);
