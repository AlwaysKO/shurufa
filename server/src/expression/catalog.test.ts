import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { describe, expect, it } from 'vitest';
import type { ExpressionAsset } from '../types/expression.js';
import { emojiCombinationKey, rankExpressionAssets } from './catalog.js';

const migration = readFileSync(fileURLToPath(
  new URL('../../migrations/011_expression_assets.sql', import.meta.url),
), 'utf8');
const upgradeMigrationPath = fileURLToPath(
  new URL('../../migrations/012_expression_asset_two_tier.sql', import.meta.url),
);
const sourceManifest = JSON.parse(readFileSync(fileURLToPath(
  new URL('../../../assets/expression/manifest.source.json', import.meta.url),
), 'utf8')) as {
  version: string;
  templates: Array<{
    keywords: string[];
    sourceCrop?: { y: number; height: number };
    layout: { minFontSize: number; maxFontSize: number; strokeWidth: number };
  }>;
};

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

  it('普通词优先返回关键词语义相关的合成模板而不是纯热度模板', () => {
    const rows = [
      asset({ id: 'hot-unrelated', heat: 999, keywords: ['开心庆祝'] }),
      asset({ id: 'glass-heart', heat: 1, keywords: ['伤心哭泣', '玻璃心'] }),
      asset({ id: 'heart', heat: 2, keywords: ['喜欢爱心'] }),
    ];

    expect(rankExpressionAssets(rows, '玻璃心').map((item) => item.id))
      .toEqual(['glass-heart', 'heart', 'hot-unrelated']);
  });

  it('完全未知词的兜底顺序按查询稳定变化避免总是同一批图', () => {
    const rows = Array.from({ length: 8 }, (_, index) => asset({ id: `tpl-${index}` }));

    const first = rankExpressionAssets(rows, '甲词').map((item) => item.id);
    const repeated = rankExpressionAssets(rows, '甲词').map((item) => item.id);
    const second = rankExpressionAssets(rows, '乙词').map((item) => item.id);

    expect(repeated).toEqual(first);
    expect(second).not.toEqual(first);
  });

  it('常用词在源清单中各自关联多张预制图片', () => {
    for (const phrase of ['你好', '谢谢', '加油', '晚安', '早安', '再见', '抱歉', '喜欢', '不要', '快点']) {
      const matches = sourceManifest.templates.filter(({ keywords }) => keywords.includes(phrase));
      expect(matches.length, phrase).toBeGreaterThanOrEqual(2);
    }
  });

  it('内置候选裁掉顶部空白并使用大号粗体友好的描边规格', () => {
    expect(sourceManifest.version).toBe('2026.09.02.1');
    for (const template of sourceManifest.templates) {
      expect(template.sourceCrop?.y).toBeGreaterThanOrEqual(150);
      expect(template.sourceCrop?.height).toBeLessThanOrEqual(234);
      expect(template.layout.minFontSize).toBeGreaterThanOrEqual(32);
      expect(template.layout.maxFontSize).toBeGreaterThanOrEqual(80);
      expect(template.layout.strokeWidth).toBeGreaterThanOrEqual(5);
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

describe('012_expression_asset_two_tier.sql', () => {
  it('从旧版素材表幂等升级并迁移旧类型', async () => {
    expect(existsSync(upgradeMigrationPath)).toBe(true);
    const upgradeMigration = readFileSync(upgradeMigrationPath, 'utf8');
    const database = newDb();
    const adapter = database.adapters.createPg();
    const pool: pg.Pool = new adapter.Pool();
    await pool.query(`CREATE TABLE expression_asset (
      id TEXT PRIMARY KEY,
      type VARCHAR(20) NOT NULL,
      format VARCHAR(10) NOT NULL,
      version TEXT NOT NULL,
      file_name TEXT NOT NULL UNIQUE,
      sha256 CHAR(64) NOT NULL,
      width INT NOT NULL,
      height INT NOT NULL,
      keywords TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      emotions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      CONSTRAINT expression_asset_type_check CHECK (type IN ('recommendation', 'template'))
    )`);
    await pool.query(`INSERT INTO expression_asset
      (id, type, format, version, file_name, sha256, width, height)
      VALUES
      ('old-recommendation', 'recommendation', 'webp', 'v1', 'old-recommendation.webp', $1, 1, 1),
      ('old-template', 'template', 'webp', 'v1', 'old-template.webp', $2, 1, 1)`, [
      'a'.repeat(64), 'b'.repeat(64),
    ]);

    await pool.query(upgradeMigration);
    await pool.query(upgradeMigration);

    expect((await pool.query(
      'SELECT id, type, embedded_text FROM expression_asset ORDER BY id',
    )).rows).toEqual([
      { id: 'old-recommendation', type: 'prebuilt', embedded_text: null },
      { id: 'old-template', type: 'synthesis-template', embedded_text: null },
    ]);
    await expect(pool.query(`INSERT INTO expression_asset
      (id, type, format, version, file_name, sha256, width, height)
      VALUES ('legacy', 'template', 'webp', 'v2', 'legacy.webp', $1, 1, 1)`, [
      'c'.repeat(64),
    ])).rejects.toThrow();
    await pool.end();
  });
});
