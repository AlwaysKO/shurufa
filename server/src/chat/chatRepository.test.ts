import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it } from 'vitest';
import type {
  CapturedConversationInput,
  CapturedMessageInput,
} from '../types/chat.js';
import { ingestCapturedMessages } from './chatRepository.js';

let pool: pg.Pool;
let userId: string;
let deviceId: string;

const conversation: CapturedConversationInput = {
  platform: 'wechat',
  account_key: 'local-account',
  external_key: 'direct:peer',
  display_name: '对方',
  conversation_type: 'direct',
  identity_confidence: 0.95,
};

function message(overrides: Partial<CapturedMessageInput> = {}): CapturedMessageInput {
  return {
    id: crypto.randomUUID(),
    fingerprint: 'a'.repeat(64),
    content_fingerprint: 'b'.repeat(64),
    sender_key: 'peer',
    sender_name: '对方',
    direction: 'incoming',
    message_type: 'text',
    text: '相同文本',
    displayed_time: '18:30',
    captured_at: new Date().toISOString(),
    ...overrides,
  };
}

beforeEach(async () => {
  const database = newDb();
  database.public.interceptQueries(sql => sql.startsWith('LOCK TABLE ') ? [] : null);
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  const sql = readFileSync(
    new URL('../../migrations/007_chat_capture.sql', import.meta.url),
    'utf8',
  );
  await pool.query(sql);
  await pool.query(readFileSync(new URL('../../migrations/022_chat_conversation_merge.sql', import.meta.url), 'utf8'));
  userId = crypto.randomUUID();
  deviceId = crypto.randomUUID();
});

describe('ingestCapturedMessages', () => {
  it.each(['wechat', 'qq', 'douyin'] as const)('新版 %s 确认重放不重复图片且低置信度重试不降级名字', async (platform) => {
    const pending = { ...conversation, platform, account_key: `${platform}-local`, external_key: `capture-v3:${'1'.repeat(64)}`, display_name: '待确认会话', identity_confidence: 0.55 };
    const image = message({ message_type: 'image', text: undefined });
    const first = await ingestCapturedMessages(pool, userId, deviceId, pending, [image]);
    const confirm = await ingestCapturedMessages(pool, userId, deviceId, { ...pending, display_name: '确认名字', identity_confidence: 0.85, conversation_type: 'group' }, [{ ...image, id: crypto.randomUUID() }]);
    expect(confirm).toMatchObject({ conversationId: first.conversationId, inserted: 0, duplicated: 1 });
    await ingestCapturedMessages(pool, userId, deviceId, pending, [image]);
    expect((await pool.query('SELECT display_name, conversation_type FROM chat_conversation')).rows[0]).toEqual({ display_name: '确认名字', conversation_type: 'group' });
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(1);
  });

  it('新截图标识先待确认后确认，迟到的待确认上传不能覆盖已确认名字', async () => {
    const pending = { ...conversation, account_key: 'wechat-empty-tree', external_key: `screenshot-v2:${'a'.repeat(64)}`, display_name: '待确认会话 aaaaaaaa', identity_confidence: 0.55 };
    const first = await ingestCapturedMessages(pool, userId, deviceId, pending, [message()]);
    const confirmed = { ...pending, display_name: '联系人甲', identity_confidence: 0.85 };
    const second = await ingestCapturedMessages(pool, userId, deviceId, confirmed, [message({ fingerprint: 'c'.repeat(64) })]);
    await ingestCapturedMessages(pool, userId, deviceId, pending, [message({ fingerprint: 'd'.repeat(64) })]);
    expect(first.conversationId).toBe(second.conversationId);
    expect((await pool.query('SELECT display_name, identity_confidence FROM chat_conversation')).rows).toEqual([
      { display_name: '联系人甲', identity_confidence: 0.85 },
    ]);
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(3);
  });

  it('新识别不按近似名字合并，也不改动旧 title 标识的记录', async () => {
    const legacy = { ...conversation, external_key: 'title:legacy', display_name: '联系人甲' };
    await ingestCapturedMessages(pool, userId, deviceId, legacy, [message()]);
    for (const [index, name] of ['联系人甲', '联系人申'].entries()) {
      await ingestCapturedMessages(pool, userId, deviceId, {
        ...conversation, external_key: `screenshot-v2:${String(index).repeat(64)}`, display_name: name,
      }, [message({ fingerprint: String(index).repeat(64) })]);
    }
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(3);
    expect((await pool.query("SELECT display_name FROM chat_conversation WHERE external_key = 'title:legacy'")).rows[0].display_name).toBe('联系人甲');
  });

  it('相同新截图标识仍按用户、平台、账号分隔', async () => {
    const external_key = `capture-v3:${'f'.repeat(64)}`;
    await ingestCapturedMessages(pool, userId, deviceId, { ...conversation, external_key }, [message()]);
    await ingestCapturedMessages(pool, crypto.randomUUID(), deviceId, { ...conversation, external_key }, [message()]);
    await ingestCapturedMessages(pool, userId, deviceId, { ...conversation, external_key, platform: 'qq' }, [message()]);
    await ingestCapturedMessages(pool, userId, deviceId, { ...conversation, external_key, account_key: 'another' }, [message({ fingerprint: 'c'.repeat(64) })]);
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(4);
  });

  it('重复写入同一批消息时保持会话和消息幂等', async () => {
    const batch = [message()];

    const first = await ingestCapturedMessages(pool, userId, deviceId, conversation, batch);
    const second = await ingestCapturedMessages(pool, userId, deviceId, conversation, batch);

    expect(first).toMatchObject({ inserted: 1, duplicated: 0, missingAssets: [] });
    expect(second).toMatchObject({ inserted: 0, duplicated: 1, missingAssets: [] });
    expect(second.conversationId).toBe(first.conversationId);
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(1);
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(1);
  });

  it('相同文本但不同消息指纹会保留两条', async () => {
    const first = message();
    const second = message({ fingerprint: 'c'.repeat(64) });

    const result = await ingestCapturedMessages(
      pool,
      userId,
      deviceId,
      conversation,
      [first, second],
    );

    expect(result).toMatchObject({ inserted: 2, duplicated: 0 });
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(2);
  });

  it('同一微信通话状态通知在短时间反复更新时只保存一条', async () => {
    const first = message({
      text: '视频通话中',
      content_fingerprint: '1'.repeat(64),
      captured_at: '2026-09-14T01:21:48.100Z',
      metadata: { capture_source: 'notification', notification_key: 'wechat-call' },
    });
    const update = message({
      fingerprint: 'c'.repeat(64),
      text: '视频通话中',
      content_fingerprint: '1'.repeat(64),
      captured_at: '2026-09-14T01:22:24.100Z',
      metadata: { capture_source: 'notification', notification_key: 'wechat-call' },
    });

    const firstResult = await ingestCapturedMessages(pool, userId, deviceId, conversation, [first]);
    const updateResult = await ingestCapturedMessages(pool, userId, deviceId, conversation, [update]);

    expect(firstResult).toMatchObject({ inserted: 1, duplicated: 0 });
    expect(updateResult).toMatchObject({ inserted: 0, duplicated: 1 });
    expect((await pool.query('SELECT text FROM chat_message')).rows).toEqual([{ text: '视频通话中' }]);
  });

  it('服务端拒绝旧客户端上传的微信隐藏占位和桌面登录提示', async () => {
    const genericConversation = { ...conversation, display_name: '微信', external_key: 'wechat-generic' };
    const hidden = message({
      text: '[有人@我]1个联系人发来1条消息',
      metadata: { capture_source: 'notification' },
    });
    const desktopLogin = message({
      fingerprint: 'c'.repeat(64),
      text: '登录 Windows 微信',
      metadata: { capture_source: 'notification' },
    });

    const result = await ingestCapturedMessages(pool, userId, deviceId, genericConversation, [hidden, desktopLogin]);

    expect(result).toMatchObject({ inserted: 0, duplicated: 2 });
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(0);
  });

  it('资源缺失时返回哈希且不提前写入相关消息', async () => {
    const missingSha256 = 'd'.repeat(64);

    const result = await ingestCapturedMessages(pool, userId, deviceId, conversation, [
      message({ message_type: 'image', asset_sha256: [missingSha256] }),
    ]);

    expect(result).toMatchObject({
      inserted: 0,
      duplicated: 0,
      missingAssets: [missingSha256],
    });
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(0);
  });

  it('拒绝超过 200 条的批次', async () => {
    const batch = Array.from({ length: 201 }, (_, index) => message({
      id: crypto.randomUUID(),
      fingerprint: index.toString(16).padStart(64, '0'),
    }));

    await expect(ingestCapturedMessages(
      pool,
      userId,
      deviceId,
      conversation,
      batch,
    )).rejects.toThrow('200');
  });
});
