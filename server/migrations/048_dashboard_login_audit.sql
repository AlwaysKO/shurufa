-- 全局后台认证审计；不关联手机，删除手机不得删除登录历史。
CREATE TABLE IF NOT EXISTS dashboard_login_audit (
  id BIGSERIAL PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  username TEXT NOT NULL CHECK (length(username) <= 200),
  authenticated_username TEXT,
  result TEXT NOT NULL CHECK (result IN ('success', 'invalid_credentials', 'rate_limited', 'rejected')),
  ip TEXT NOT NULL,
  peer_ip TEXT NOT NULL,
  ip_source TEXT NOT NULL CHECK (ip_source IN ('connection', 'local_proxy')),
  user_agent TEXT NOT NULL CHECK (length(user_agent) <= 1000)
);
CREATE INDEX IF NOT EXISTS dashboard_login_audit_time ON dashboard_login_audit(occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS dashboard_login_audit_ip_time ON dashboard_login_audit(ip, occurred_at DESC, id DESC);
