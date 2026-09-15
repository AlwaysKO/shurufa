CREATE TABLE IF NOT EXISTS relationship_ai_profile (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    version INT NOT NULL CHECK (version > 0),
    profile JSONB NOT NULL,
    source_message_count INT NOT NULL CHECK (source_message_count >= 0),
    source_last_message_at TIMESTAMPTZ,
    model VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, conversation_id, version)
);
CREATE INDEX IF NOT EXISTS idx_relationship_ai_profile_latest
    ON relationship_ai_profile(user_id, conversation_id, version DESC);

CREATE TABLE IF NOT EXISTS relationship_ai_call (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id BIGINT REFERENCES chat_conversation(id) ON DELETE SET NULL,
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('profile', 'reply')),
    model VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('success', 'failed')),
    prompt_tokens INT,
    completion_tokens INT,
    duration_ms INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_relationship_ai_call_budget
    ON relationship_ai_call(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS relationship_ai_reply_session (
    user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    context_hash CHAR(64) NOT NULL,
    refresh_count INT NOT NULL DEFAULT 0 CHECK (refresh_count BETWEEN 0 AND 20),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, session_id)
);
