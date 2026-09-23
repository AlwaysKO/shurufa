/** 历史归属审核：仅只读，不支持 apply，不加载项目 .env。 */
import pg from 'pg';
import { auditPendingScreenshots } from '../src/chat/pendingScreenshotRecovery.js';

const usage = '用法：CHAT_RECOVERY_DATABASE_URL=<显式审核连接> npx tsx scripts/audit-pending-screenshots.ts --dry-run --user <UUID> --device <UUID> --platform <wechat|qq|douyin> --account <account_key>';
async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help')) { console.log(usage); return; }
  if (!args.includes('--dry-run') || args.includes('--apply')) throw Error(usage);
  const allowed = new Set(['--dry-run', '--user', '--device', '--platform', '--account']);
  const values = new Map<string, string>();
  for (let i = 0; i < args.length; i++) {
    const key = args[i];
    if (!allowed.has(key)) throw Error(usage);
    if (key === '--dry-run') continue;
    const value = args[++i];
    if (!value || value.startsWith('--') || values.has(key)) throw Error(usage);
    values.set(key, value);
  }
  const userId = values.get('--user'), deviceId = values.get('--device');
  const platform = values.get('--platform'), accountKey = values.get('--account');
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
  if (!userId || !deviceId || !uuid.test(userId) || !uuid.test(deviceId) ||
    !platform || !['wechat', 'qq', 'douyin'].includes(platform) || !accountKey || !process.env.CHAT_RECOVERY_DATABASE_URL) throw Error(usage);
  const pool = new pg.Pool({ connectionString: process.env.CHAT_RECOVERY_DATABASE_URL, max: 1 });
  const client = await pool.connect();
  try {
    await client.query('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY');
    await client.query("SET LOCAL statement_timeout='60s'");
    const report = await auditPendingScreenshots(client, { userId, deviceId, platform, accountKey });
    await client.query('COMMIT');
    console.log(JSON.stringify({ mode: 'dry-run', scope: { userId, deviceId, platform, accountKey }, ...report }, null, 2));
  } catch (error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); await pool.end(); }
}
main().catch(error => { console.error(error instanceof Error ? error.message : '审核失败'); process.exitCode = 1; });
