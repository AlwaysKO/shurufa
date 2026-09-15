CREATE TABLE IF NOT EXISTS relationship_profile (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    relationship_type VARCHAR(30) NOT NULL DEFAULT 'unknown'
        CHECK (relationship_type IN (
            'unknown', 'friend', 'family', 'partner', 'colleague',
            'customer', 'group', 'other'
        )),
    alias VARCHAR(100),
    intimacy_level INT NOT NULL DEFAULT 50
        CHECK (intimacy_level BETWEEN 0 AND 100),
    humor_level INT NOT NULL DEFAULT 50
        CHECK (humor_level BETWEEN 0 AND 100),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_relationship_profile_user_type
    ON relationship_profile(user_id, relationship_type);
