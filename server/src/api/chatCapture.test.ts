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

const bytes = Buffer.from('524946460000000057454250', 'hex');
const actualSha256 = createHash('sha256').update(bytes).digest('hex');

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(
    new URL('../../migrations/007_chat_capture.sql', import.meta.url),
    'utf8',
  ));
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

    expect((await request(app).post('/api/v1/mobile/chat/assets').send(payload)).body)
      .toMatchObject({ ok: true, duplicated: false, sha256: actualSha256 });
    expect((await request(app).post('/api/v1/mobile/chat/assets').send(payload)).body)
      .toMatchObject({ ok: true, duplicated: true, sha256: actualSha256 });
    expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(1);
    const files = await readdir(join(root, 'uploads', 'chat', actualSha256.slice(0, 2)));
    expect(files).toEqual([`${actualSha256}.webp`]);
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
});
