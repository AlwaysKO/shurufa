import { readFileSync } from 'node:fs';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import type { GeneratedExpressionCatalog } from '../expression/assetGenerator.js';

const USER_A = '00000000-0000-4000-8000-00000000000a';
const USER_B = '00000000-0000-4000-8000-00000000000b';
let pool: pg.Pool;
let root: string;

const catalog: GeneratedExpressionCatalog = {
  version: 'api-v1',
  templates: [
    {
      id: 'hot', type: 'template', format: 'webp', version: 'api-v1',
      fileName: 'templates/hot.webp', thumbnailFileName: 'thumbnails/hot.webp',
      sha256: 'a'.repeat(64), width: 512, height: 512,
      keywords: [], emotions: [], textSafeArea: null, layout: null, heat: 100,
    },
    {
      id: 'emotion', type: 'template', format: 'gif', version: 'api-v1',
      fileName: 'templates/emotion.gif', thumbnailFileName: 'thumbnails/emotion.webp',
      sha256: 'b'.repeat(64), width: 512, height: 512,
      keywords: [], emotions: ['放箭'], textSafeArea: null, layout: null, heat: 1,
    },
    {
      id: 'exact', type: 'template', format: 'webp', version: 'api-v1',
      fileName: 'templates/exact.webp', thumbnailFileName: 'thumbnails/exact.webp',
      sha256: 'c'.repeat(64), width: 512, height: 512,
      keywords: ['放箭'], emotions: [], textSafeArea: null, layout: null, heat: 0,
    },
  ],
  emojiBases: [
    {
      id: 'angry', name: '生气', emotions: ['angry'],
      fileName: 'emoji-base/angry.webp', sha256: 'd'.repeat(64), version: 'api-v1',
      width: 256, height: 256, sortOrder: 0,
    },
    {
      id: 'cry', name: '哭泣', emotions: ['sad'],
      fileName: 'emoji-base/cry.webp', sha256: 'e'.repeat(64), version: 'api-v1',
      width: 256, height: 256, sortOrder: 1,
    },
  ],
  emojiCombinations: [
    {
      key: 'angry__cry', firstId: 'angry', secondId: 'cry',
      fileName: 'emoji-combinations/angry__cry.webp', sha256: 'f'.repeat(64),
      version: 'api-v1', width: 256, height: 256, heat: 0,
    },
    {
      key: 'cry__angry', firstId: 'cry', secondId: 'angry',
      fileName: 'emoji-combinations/cry__angry.webp', sha256: '0'.repeat(64),
      version: 'api-v1', width: 256, height: 256, heat: 0,
    },
  ],
};

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(
    new URL('../../migrations/011_expression_assets.sql', import.meta.url),
    'utf8',
  ));
  root = await mkdtemp(join(tmpdir(), 'expressions-api-'));
  vi.spyOn(process, 'cwd').mockReturnValue(root);
  const runtimeRoot = join(root, '.runtime', 'expression-assets');
  await mkdir(join(runtimeRoot, 'emoji-combinations'), { recursive: true });
  await mkdir(join(runtimeRoot, 'templates'), { recursive: true });
  await mkdir(join(runtimeRoot, 'thumbnails'), { recursive: true });
  await writeFile(join(runtimeRoot, 'catalog.json'), JSON.stringify(catalog));
  await writeFile(join(runtimeRoot, 'emoji-combinations', 'angry__cry.webp'), 'forward');
  await writeFile(join(runtimeRoot, 'emoji-combinations', 'cry__angry.webp'), 'reverse');
});

afterEach(async () => {
  vi.restoreAllMocks();
  await pool.end();
  await rm(root, { recursive: true, force: true });
});

describe('mobile expression API', () => {
  it('返回目录并在版本未变化时返回 304', async () => {
    const app = createApp(pool);
    const response = await request(app)
      .get('/api/v1/mobile/expressions/catalog')
      .set('X-Device-Id', USER_A);
    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ version: 'api-v1' });
    expect(response.body.templates).toHaveLength(3);

    const unchanged = await request(app)
      .get('/api/v1/mobile/expressions/catalog?version=api-v1')
      .set('X-Device-Id', USER_A);
    expect(unchanged.status).toBe(304);
  });

  it('推荐最多返回 20 项并按精确关键词、情绪和热度排序', async () => {
    const response = await request(createApp(pool))
      .get('/api/v1/mobile/expressions/recommend?q=放箭')
      .set('X-Device-Id', USER_A);

    expect(response.status).toBe(200);
    expect(response.body.results.map((item: { id: string }) => item.id))
      .toEqual(['exact', 'emotion', 'hot']);
    expect(response.body.results[0]).toMatchObject({
      url: '/uploads/expression/templates/exact.webp',
      thumbnail_url: '/uploads/expression/thumbnails/exact.webp',
    });
    expect(response.body.results.length).toBeLessThanOrEqual(20);
  });

  it('只按有序键返回 Emoji 组合且未知组合为 404', async () => {
    const app = createApp(pool);
    const forward = await request(app)
      .get('/api/v1/mobile/expressions/emoji/angry/cry')
      .set('X-Device-Id', USER_A);
    const reverse = await request(app)
      .get('/api/v1/mobile/expressions/emoji/cry/angry')
      .set('X-Device-Id', USER_A);

    expect(forward.status).toBe(200);
    expect(reverse.status).toBe(200);
    expect(forward.body.key).toBe('angry__cry');
    expect(reverse.body.key).toBe('cry__angry');
    expect(forward.body.url).not.toBe(reverse.body.url);
    expect((await request(app)
      .get('/api/v1/mobile/expressions/emoji/angry/missing')
      .set('X-Device-Id', USER_A)).status).toBe(404);
  });

  it('按用户隔离使用次数且连续上报累加', async () => {
    const app = createApp(pool);
    for (const userId of [USER_A, USER_A, USER_B]) {
      const response = await request(app)
        .post('/api/v1/mobile/expressions/exact/use')
        .set('X-Device-Id', userId);
      expect(response.status).toBe(200);
    }

    const rows = await pool.query<{ user_id: string; use_count: number }>(
      'SELECT user_id, use_count FROM expression_asset_usage ORDER BY user_id',
    );
    expect(rows.rows).toEqual([
      expect.objectContaining({ user_id: USER_A, use_count: 2 }),
      expect.objectContaining({ user_id: USER_B, use_count: 1 }),
    ]);
  });

  it('拒绝非法 ID、空查询、超长查询和缺失身份', async () => {
    const app = createApp(pool);
    expect((await request(app)
      .post('/api/v1/mobile/expressions/bad$id/use')
      .set('X-Device-Id', USER_A)).status).toBe(400);
    expect((await request(app)
      .get('/api/v1/mobile/expressions/recommend')
      .set('X-Device-Id', USER_A)).status).toBe(400);
    expect((await request(app)
      .get(`/api/v1/mobile/expressions/recommend?q=${'a'.repeat(101)}`)
      .set('X-Device-Id', USER_A)).status).toBe(400);
    expect((await request(app)
      .get('/api/v1/mobile/expressions/catalog')).status).toBe(400);
  });

  it('素材文件要求合法移动端身份', async () => {
    const app = createApp(pool);
    const path = '/uploads/expression/emoji-combinations/angry__cry.webp';
    expect((await request(app).get(path)).status).toBe(400);
    const response = await request(app).get(path).set('X-Device-Id', USER_A);
    expect(response.status).toBe(200);
    expect(response.body).toEqual(Buffer.from('forward'));
  });
});
