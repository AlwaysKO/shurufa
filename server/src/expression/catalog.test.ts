import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { describe, expect, it } from 'vitest';
import type { ExpressionAsset } from '../types/expression.js';
import { emojiCombinationKey, rankExpressionAssets } from './catalog.js';

const migration = readFileSync(fileURLToPath(
  new URL('../../migrations/011_expression_assets.sql', import.meta.url),
), 'utf8');
const sourceManifest = JSON.parse(readFileSync(fileURLToPath(
  new URL('../../../assets/expression/manifest.source.json', import.meta.url),
), 'utf8')) as { templates: Array<{ keywords: string[] }> };

function asset(overrides: Partial<ExpressionAsset>): ExpressionAsset {
  return {
    id: 'asset',
    type: 'synthesis-template',
    format: 'webp',
    version: 'v1',
    fileName: 'asset.webp',
    thumbnailFileName: null,
    sha256: 'a'.repeat(64),
    width: 256,
    height: 256,
    keywords: [],
    emotions: [],
    embeddedText: null,
    textSafeArea: null,
    layout: null,
    heat: 0,
    ...overrides,
  };
}

describe('expression catalog', () => {
  it('保留 Emoji 组合选择顺序', () => {
    expect(emojiCombinationKey('angry', 'cry')).toBe('angry__cry');
    expect(emojiCombinationKey('cry', 'angry')).toBe('cry__angry');
  });

  it('优先返回 embeddedText 完整精确匹配的预制图', () => {
    const rows = [
      asset({ id: 'fallback', heat: 100 }),
      asset({ id: 'unrelated', type: 'prebuilt', embeddedText: '谢谢', heat: 100 }),
      asset({ id: 'exact-low', type: 'prebuilt', embeddedText: '放箭', heat: 1 }),
      asset({ id: 'exact-high', type: 'prebuilt', embeddedText: '放箭', heat: 10 }),
    ];

    expect(rankExpressionAssets(rows, ' 放箭 ').map((item) => item.id))
      .toEqual(['exact-high', 'exact-low']);
  });

  it('无预制结果时只返回限定数量的静态合成模板', () => {
    const rows = [
      asset({ id: 'cold', heat: 1 }),
      asset({ id: 'hot', heat: 10 }),
      asset({ id: 'unrelated', type: 'prebuilt', embeddedText: '你好', heat: 100 }),
    ];

    expect(rankExpressionAssets(rows, '今天的云像棉花糖', 1).map((item) => item.id)).toEqual(['hot']);
    expect(rows.map((item) => item.id)).toEqual(['cold', 'hot', 'unrelated']);
  });

  it('常用词在源清单中各自关联多张预制图片', () => {
    for (const phrase of ['你好', '谢谢', '加油', '晚安', '早安', '再见', '抱歉', '喜欢', '不要', '快点']) {
      const matches = sourceManifest.templates.filter(({ keywords }) => keywords.includes(phrase));
      expect(matches.length, phrase).toBeGreaterThanOrEqual(2);
    }
  });
});

describe('011_expression_assets.sql', () => {
  it('可重复执行并保留有序组合与用户级使用次数', async () => {
    const database = newDb();
    const adapter = database.adapters.createPg();
    const pool: pg.Pool = new adapter.Pool();

    expect(migration.match(/CREATE TABLE IF NOT EXISTS/g)).toHaveLength(4);
    expect(migration.match(/CREATE INDEX IF NOT EXISTS/g)).toHaveLength(5);
    // pg-mem 不支持重复规划带约束的 CREATE TABLE IF NOT EXISTS；PostgreSQL
    // 的幂等性由上述 DDL 形式保证，这里执行一次验证实际结构。
    await pool.query(migration);
    await pool.query(`INSERT INTO emoji_base
      (id, name, emotions, file_name, sha256, version, sort_order)
      VALUES
      ('angry', '生气', ARRAY['angry'], 'angry.webp', $1, 'v1', 0),
      ('cry', '哭泣', ARRAY['sad'], 'cry.webp', $2, 'v1', 1)`, [
      'a'.repeat(64), 'b'.repeat(64),
    ]);
    await pool.query(`INSERT INTO emoji_combination
      (first_id, second_id, file_name, sha256, version)
      VALUES
      ('angry', 'cry', 'angry__cry.webp', $1, 'v1'),
      ('cry', 'angry', 'cry__angry.webp', $2, 'v1')`, [
      'c'.repeat(64), 'd'.repeat(64),
    ]);
    expect((await pool.query('SELECT first_id, second_id FROM emoji_combination')).rowCount)
      .toBe(2);

    await pool.query(`INSERT INTO expression_asset
      (id, type, format, version, file_name, sha256, width, height,
       keywords, emotions, embedded_text)
      VALUES ('exact', 'prebuilt', 'webp', 'v1', 'exact.webp', $1, 256, 256,
              ARRAY['放箭'], ARRAY['happy'], '放箭')`, ['e'.repeat(64)]);
    expect((await pool.query(
      "SELECT type, embedded_text FROM expression_asset WHERE id = 'exact'",
    )).rows).toEqual([{ type: 'prebuilt', embedded_text: '放箭' }]);
    const firstUser = crypto.randomUUID();
    const secondUser = crypto.randomUUID();
    await pool.query(`INSERT INTO expression_asset_usage (user_id, asset_id, use_count)
      VALUES ($1, 'exact', 3), ($2, 'exact', 7)`, [firstUser, secondUser]);

    const usages = await pool.query<{ user_id: string; use_count: number }>(
      'SELECT user_id, use_count FROM expression_asset_usage ORDER BY use_count',
    );
    expect(usages.rows).toEqual([
      expect.objectContaining({ user_id: firstUser, use_count: 3 }),
      expect.objectContaining({ user_id: secondUser, use_count: 7 }),
    ]);
    await pool.end();
  });
});
