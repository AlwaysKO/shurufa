const base = 'http://127.0.0.1:3010';
const request = async (path, options = {}) => {
  const response = await fetch(`${base}${path}`, { ...options, signal: AbortSignal.timeout(2000) });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return response;
};
let cookie;
try {
  await request('/health');
  const login = await request('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Dashboard-Request': '1' },
    body: JSON.stringify({ username: process.env.DASHBOARD_USERNAME, password: process.env.DASHBOARD_PASSWORD }),
  });
  cookie = login.headers.getSetCookie().find(value => value.startsWith('dashboard_session='))?.split(';')[0];
  if (!cookie) throw new Error('Login did not return a session cookie');
  await request('/api/v1/dashboard/users', { headers: { Cookie: cookie } });
} catch (error) {
  console.error(`Deployment health check failed: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (cookie) {
    try {
      await request('/api/v1/auth/logout', {
        method: 'POST', headers: { Cookie: cookie, 'X-Dashboard-Request': '1' },
      });
    } catch (error) {
      console.error(`Health check session cleanup failed: ${error.message}`);
      process.exitCode = 1;
    }
  }
}
