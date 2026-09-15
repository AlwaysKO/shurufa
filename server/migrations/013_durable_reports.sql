-- Receipts share the transaction with their effect; retaining IDs prevents late retries duplicating counters.
CREATE TABLE IF NOT EXISTS mobile_report_receipt (
 user_id UUID NOT NULL, report_id UUID NOT NULL, payload_hash TEXT NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(user_id, report_id)
);

CREATE TABLE IF NOT EXISTS personal_candidate_usage (
 user_id UUID NOT NULL, code TEXT NOT NULL, text TEXT NOT NULL,
 count BIGINT NOT NULL, weight DOUBLE PRECISION NOT NULL, last_used BIGINT NOT NULL,
 PRIMARY KEY(user_id,code,text)
);

-- Keep feedback even when a destination does not yet have the cloud candidate/asset.
CREATE TABLE IF NOT EXISTS completion_feedback_usage (
 user_id UUID NOT NULL, prefix TEXT NOT NULL, completion TEXT NOT NULL,
 accept_count BIGINT NOT NULL DEFAULT 0, show_count BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,prefix,completion)
);
CREATE TABLE IF NOT EXISTS sticker_file_usage (
 user_id UUID NOT NULL, file_name TEXT NOT NULL, use_count BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,file_name)
);
