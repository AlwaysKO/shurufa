-- 私人输入法 V1 数据库结构
-- 6 张核心表：device / input_session / input_event / phrase_stat / completion_candidate / analysis_state

-- 设备
CREATE TABLE IF NOT EXISTS device (
    id             UUID PRIMARY KEY,
    name           VARCHAR(255),
    platform       VARCHAR(50)  DEFAULT 'android',
    model          VARCHAR(255),
    os_version     VARCHAR(50),
    app_version    VARCHAR(50),
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 输入会话（一次键盘展示/隐藏周期）
CREATE TABLE IF NOT EXISTS input_session (
    id           UUID PRIMARY KEY,
    device_id    UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    started_at   TIMESTAMPTZ NOT NULL,
    ended_at     TIMESTAMPTZ,
    package_name VARCHAR(255),
    editor_id    VARCHAR(255),
    event_count  INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_session_device ON input_session(device_id, started_at);

-- 输入事件（append-only 原始行为日志）
CREATE TABLE IF NOT EXISTS input_event (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    device_id    UUID NOT NULL,
    session_id   UUID,
    sequence_no  BIGINT,

    occurred_at  TIMESTAMPTZ NOT NULL,

    package_name VARCHAR(255),
    editor_id    VARCHAR(255),

    event_type   VARCHAR(50) NOT NULL,
    source       VARCHAR(50),
    source_confidence NUMERIC(3,2),

    text         TEXT,
    text_before  TEXT,
    text_after   TEXT,

    input_code   TEXT,

    clipboard_id UUID,

    metadata     JSONB NOT NULL DEFAULT '{}',

    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_event_device_time ON input_event(device_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_event_type ON input_event(event_type);
CREATE INDEX IF NOT EXISTS idx_event_pkg ON input_event(package_name);
CREATE INDEX IF NOT EXISTS idx_event_user_time ON input_event(user_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_event_metadata ON input_event USING GIN(metadata);

-- 高频词/短语统计（分析结果）
CREATE TABLE IF NOT EXISTS phrase_stat (
    id           BIGSERIAL PRIMARY KEY,
    user_id      UUID NOT NULL,
    phrase       TEXT NOT NULL,
    package_name VARCHAR(255),
    use_count    INT NOT NULL DEFAULT 0,
    use_days     INT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    score        NUMERIC(8,4) NOT NULL DEFAULT 0,
    UNIQUE (user_id, phrase, package_name)
);
CREATE INDEX IF NOT EXISTS idx_phrase_score ON phrase_stat(user_id, score DESC);

-- 补全候选（同步到手机的个人补全模型）
CREATE TABLE IF NOT EXISTS completion_candidate (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    prefix          TEXT NOT NULL,
    prefix_pinyin   TEXT,
    prefix_initials TEXT,
    completion      TEXT NOT NULL,
    package_name    VARCHAR(255),
    use_count       INT NOT NULL DEFAULT 0,
    show_count      INT NOT NULL DEFAULT 0,
    accept_count    INT NOT NULL DEFAULT 0,
    score           NUMERIC(8,4) NOT NULL DEFAULT 0,
    last_used_at    TIMESTAMPTZ,
    version         INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, prefix, completion, package_name)
);
CREATE INDEX IF NOT EXISTS idx_completion_prefix ON completion_candidate(user_id, prefix, score DESC);

-- 分析游标（增量分析状态）
CREATE TABLE IF NOT EXISTS analysis_state (
    id         SERIAL PRIMARY KEY,
    key        VARCHAR(100) UNIQUE NOT NULL,
    value      BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
