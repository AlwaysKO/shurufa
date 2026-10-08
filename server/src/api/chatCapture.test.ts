import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';

let pool: pg.Pool;
let root: string;
const deviceId = '00000000-0000-4000-8000-000000000001';

const bytes = Buffer.from('524946460000000057454250', 'hex');
const actualSha256 = createHash('sha256').update(bytes).digest('hex');

beforeEach(async () => {
  const database = newDb();
  // pg-mem不实现LOCK TABLE；真实并发锁行为另由deviceControls.postgres.test覆盖。
  database.public.interceptQueries(sql => sql === 'LOCK TABLE media_asset IN ROW EXCLUSIVE MODE' ? [] : null);
  database.public.interceptQueries(sql => sql.startsWith('LOCK TABLE ') ? [] : null);
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(
    new URL('../../migrations/007_chat_capture.sql', import.meta.url),
    'utf8',
  ));
  await pool.query(readFileSync(new URL('../../migrations/022_chat_conversation_merge.sql', import.meta.url), 'utf8'));
  root = await mkdtemp(join(tmpdir(), 'chat-api-'));
  vi.spyOn(process, 'cwd').mockReturnValue(root);
});

afterEach(async () => {
  vi.restoreAllMocks();
  await pool.end();
  await rm(root, { recursive: true, force: true });
});

describe('mobile chat capture API', () => {
  it('客户端声明哈希不匹配时返回 400', async () => {
    const response = await request(createApp(pool))
      .post('/api/v1/mobile/chat/assets')
      .set('X-Device-Id', deviceId)
      .send({
        sha256: '0'.repeat(64),
        mime_type: 'image/webp',
        file_base64: bytes.toString('base64'),
      });

    expect(response.status).toBe(400);
    expect(response.body.error).toContain('sha256');
  });

  it('同一文件重复上传时返回 duplicated 且只写一份', async () => {
    const app = createApp(pool);
    const payload = {
      sha256: actualSha256,
      mime_type: 'image/webp',
      file_base64: bytes.toString('base64'),
    };

    expect((await request(app).post('/api/v1/mobile/chat/assets').set('X-Device-Id', deviceId).send(payload)).body)
      .toMatchObject({ ok: true, duplicated: false, sha256: actualSha256 });
    expect((await request(app).post('/api/v1/mobile/chat/assets').set('X-Device-Id', deviceId).send(payload)).body)
      .toMatchObject({ ok: true, duplicated: true, sha256: actualSha256 });
    expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(1);
    const files = await readdir(join(root, 'uploads', 'chat', actualSha256.slice(0, 2)));
    expect(files).toEqual([`${actualSha256}.webp`]);

    const assetPath = `/uploads/chat/${actualSha256.slice(0, 2)}/${actualSha256}.webp`;
    expect((await request(app).get(assetPath)).status).toBe(401);
    expect((await request(app).get(assetPath).set('X-Device-Id', crypto.randomUUID())).status).toBe(404);
    expect((await request(app).get(assetPath).set('X-Device-Id', deviceId)).status).toBe(200);
  });

  it('批量消息重复提交时返回正确计数', async () => {
    const app = createApp(pool);
    const payload = {
      device_id: crypto.randomUUID(),
      conversation: {
        platform: 'wechat',
        account_key: 'account',
        external_key: 'peer',
        display_name: '对方',
        conversation_type: 'direct',
        identity_confidence: 0.95,
      },
      messages: [{
        id: crypto.randomUUID(),
        fingerprint: 'a'.repeat(64),
        content_fingerprint: 'b'.repeat(64),
        sender_key: 'peer',
        direction: 'incoming',
        message_type: 'text',
        text: '你好',
        captured_at: new Date().toISOString(),
      }],
    };

    expect((await request(app).post('/api/v1/mobile/chat/messages/batch').send(payload)).body)
      .toMatchObject({ ok: true, inserted: 1, duplicated: 0, missingAssets: [] });
    expect((await request(app).post('/api/v1/mobile/chat/messages/batch').send(payload)).body)
      .toMatchObject({ ok: true, inserted: 0, duplicated: 1, missingAssets: [] });
  });

  it('旧版输入状态截图返回成功确认，不要求客户端重传', async () => {
    const response = await request(createApp(pool)).post('/api/v1/mobile/chat/messages/batch').send({
      device_id: deviceId,
      conversation: { platform: 'wechat', account_key: 'wechat-empty-tree',
        external_key: 'screenshot-v2:truncated:old', display_name: '对方正在輸入…（名称被截断）',
        conversation_type: 'unknown', identity_confidence: 0.55 },
      messages: [{ id: crypto.randomUUID(), fingerprint: 'd'.repeat(64), content_fingerprint: 'e'.repeat(64),
        sender_key: 'viewport', direction: 'system', message_type: 'image', captured_at: new Date().toISOString(),
        asset_sha256: ['f'.repeat(64)], metadata: { capture_source: 'wechat_empty_tree_screenshot' } }],
    });
    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ ok: true, conversationId: null, inserted: 0, duplicated: 1, missingAssets: [] });
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(0);
  });

  it.each([1, 61])('缺少%i张截图时保留409及完整缺失清单，仅记录有界元数据诊断', async count => {
    const missingHashes = Array.from({ length: count }, (_, i) => i.toString(16).padStart(64, '0'));
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const missingDevice = crypto.randomUUID();
    const response = await request(createApp(pool))
      .post('/api/v1/mobile/chat/messages/batch')
      .send({
        device_id: missingDevice,
        conversation: {
          platform: 'wechat',
          account_key: 'account',
          external_key: 'direct-visible-title:阿明',
          display_name: '阿明',
          conversation_type: 'direct',
          identity_confidence: 0.95,
        },
        messages: [{
          id: crypto.randomUUID(),
          fingerprint: 'd'.repeat(64),
          content_fingerprint: 'e'.repeat(64),
          sender_key: 'viewport',
          direction: 'system',
          message_type: 'image',
          captured_at: new Date().toISOString(),
          asset_sha256: missingHashes,
        }],
      });

    expect(response.status).toBe(409);
    expect(response.body).toMatchObject({ ok: false, missingAssets: missingHashes });
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(0);
    expect(warning).toHaveBeenCalledWith('[chat-missing-assets]', expect.objectContaining({
      device_id: missingDevice, platform: 'wechat', message_count: 1,
      inserted: 0, duplicated: 0, missing_count: count,
      missing_assets: missingHashes.slice(0, 50), truncated: count > 50,
    }));
    const diagnostic = JSON.stringify(warning.mock.calls);
    expect(diagnostic).not.toContain('阿明');
    expect(diagnostic).not.toContain('external_key');
    expect(diagnostic).not.toContain('sender_key');
  });

  it.each(['语音通话中', '视频通话中'])('旧版 %s 通知返回成功并丢弃，不创建待确认会话或要求补图', async text => {
    const app = createApp(pool);
    const payload = {
      device_id: deviceId,
      conversation: { platform: 'wechat', account_key: 'notification', external_key: 'notification-v2:pending:test',
        display_name: '待确认通知（测试联系人）', conversation_type: 'direct', identity_confidence: 0.55 },
      messages: [{ id: crypto.randomUUID(), fingerprint: 'a'.repeat(64), content_fingerprint: 'b'.repeat(64),
        sender_key: 'peer', direction: 'incoming', message_type: 'voice', text, captured_at: new Date().toISOString(),
        asset_sha256: ['f'.repeat(64)], metadata: { capture_source: 'notification' } }],
    };
    for (let attempt = 0; attempt < 2; attempt++) {
      const response = await request(app).post('/api/v1/mobile/chat/messages/batch').send(payload);
      expect(response.status).toBe(200);
      expect(response.body).toMatchObject({ ok: true, conversationId: null, inserted: 0, duplicated: 1, missingAssets: [] });
    }
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(0);
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(0);
  });
});
