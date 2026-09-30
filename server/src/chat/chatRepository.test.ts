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
  it.each(['对方正在输入…（名称被截断）', '对方正在輸入…（名称被截断）', '對方 正在輸入..8'])('旧版状态截图 %s 成功处理但不创建会话或索要素材', async (title) => {
    const screenshot = message({ direction: 'system', message_type: 'image', text: undefined,
      asset_sha256: ['f'.repeat(64)], metadata: { capture_source: 'wechat_empty_tree_screenshot' } });
    const result = await ingestCapturedMessages(pool, userId, deviceId,
      { ...conversation, display_name: title }, [screenshot]);
    expect(result).toMatchObject({ conversationId: null, inserted: 0, duplicated: 1, missingAssets: [] });
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(0);
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(0);
  });

  it('同批状态截图按 observed_title 丢弃，普通正文及正常图片仍保留', async () => {
    const typing = message({ direction: 'system', message_type: 'image', text: undefined,
      asset_sha256: ['f'.repeat(64)], metadata: { capture_source: 'notification_screenshot_fallback',
        conversation_identity_observed_title: '对方正在輸入...' } });
    const text = message({ fingerprint: 'c'.repeat(64), text: '对方正在輸入...' });
    const normal = message({ fingerprint: 'd'.repeat(64), message_type: 'image', text: undefined,
      metadata: { capture_source: 'wechat_empty_tree_screenshot', conversation_identity_observed_title: '正常联系人' } });
    const result = await ingestCapturedMessages(pool, userId, deviceId, conversation, [typing, text, normal]);
    expect(result).toMatchObject({ inserted: 2, duplicated: 1, missingAssets: [] });
    expect((await pool.query('SELECT fingerprint FROM chat_message')).rows.map(row => row.fingerprint).sort())
      .toEqual([text.fingerprint, normal.fingerprint]);
  });

  it('姓名中间包含输入状态字样不误拒绝', async () => {
    const result = await ingestCapturedMessages(pool, userId, deviceId,
      { ...conversation, display_name: '讨论对方正在輸入' }, [message({ message_type: 'image',
        metadata: { capture_source: 'wechat_empty_tree_screenshot' } })]);
    expect(result.inserted).toBe(1);
  });

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

  it('通话中状态通知包括重复更新均不保存或创建会话', async () => {
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

    expect(firstResult).toMatchObject({ conversationId: null, inserted: 0, duplicated: 1 });
    expect(updateResult).toMatchObject({ conversationId: null, inserted: 0, duplicated: 1 });
    expect((await pool.query('SELECT text FROM chat_message')).rowCount).toBe(0);
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(0);
  });

  it.each(['wechat', 'qq', 'douyin'] as const)('%s 过滤旧版通话状态且不索要素材，混合批次保留普通消息', async platform => {
    const status = message({ text: '语音通话中', message_type: 'voice', asset_sha256: ['f'.repeat(64)], metadata: { capture_source: 'notification' } });
    const voice = message({ fingerprint: 'c'.repeat(64), text: '[语音]', message_type: 'voice', metadata: { capture_source: 'notification' } });
    const chat = message({ fingerprint: 'd'.repeat(64), text: '语音通话中', metadata: { capture_source: 'notification', notification_messaging_style: 'true' } });
    const ui = message({ fingerprint: 'e'.repeat(64), text: '视频通话中', metadata: { capture_source: 'accessibility' } });
    const normal = message({ fingerprint: 'f'.repeat(64), text: '语音通话中听不清', metadata: { capture_source: 'notification' } });
    expect(await ingestCapturedMessages(pool,userId,deviceId,{...conversation,platform},[status,voice,chat,ui,normal]))
      .toMatchObject({inserted:4,duplicated:1,missingAssets:[]});
    expect((await pool.query('SELECT text FROM chat_message')).rows.map(r=>r.text)).toEqual(['[语音]','语音通话中','视频通话中','语音通话中听不清']);
  });

  it('兼容旧版消息时间戳，真实MessagingStyle同名文字保留且不作通话状态去重', async () => {
    const metadata = { capture_source: 'notification', notification_message_timestamp: '1700000000000' };
    const first = message({text:'语音通话中',metadata});
    const second = message({text:'语音通话中',fingerprint:'c'.repeat(64),metadata});
    expect(await ingestCapturedMessages(pool,userId,deviceId,conversation,[first,second])).toMatchObject({inserted:2,duplicated:0});
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
