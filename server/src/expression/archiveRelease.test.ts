import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { rankExpressionAssets } from './catalog.js';
import type { GeneratedExpressionCatalog } from './assetGenerator.js';

const root = resolve(import.meta.dirname, '../../..');
const readJson = async (path: string) => JSON.parse(await readFile(resolve(root, path), 'utf8'));

describe('184张批准归档正式发布', () => {
  it('正式源与APK索引含全部批准ID，按词至多4张原GIF内置且SHA一致', async () => {
    const archive = await readJson('server/images/index.json');
    const published = archive.items.filter((item: { status: string }) => item.status === 'published');
    const manifest = await readJson('assets/expression/manifest.source.json');
    const android = 'android/YuyanIme/yuyansdk/src/main/assets/expression';
    const apk: GeneratedExpressionCatalog = await readJson(`${android}/catalog.json`);
    expect(published).toHaveLength(184);
    expect(apk.version).toBe(manifest.version);
    const counts = new Map<string, number>();
    for (const source of published) {
      const item = apk.templates.find(item => item.id === source.id);
      expect(item).toMatchObject({ sha256: source.sha256, embeddedText: source.keyword,
        distribution: source.plannedDistribution, format: 'gif', type: 'prebuilt' });
      const sourceItem = manifest.prebuiltAssets.find((candidate: { id: string }) => candidate.id === source.id);
      expect(sourceItem.sha256).toBe(source.sha256);
      const bytes = await readFile(resolve(root, 'assets/expression', sourceItem.source));
      expect(createHash('sha256').update(bytes).digest('hex')).toBe(source.sha256);
      if (item!.distribution === 'remote') {
        await expect(readFile(resolve(root, android, item!.fileName))).rejects.toMatchObject({ code: 'ENOENT' });
      } else {
        expect((await readFile(resolve(root, android, item!.fileName))).equals(bytes)).toBe(true);
        counts.set(source.keyword, (counts.get(source.keyword) ?? 0) + 1);
      }
    }
    expect([...counts.values()].reduce((a, b) => a + b, 0)).toBe(124);
    for (const [keyword, count] of counts) {
      expect(count).toBeLessThanOrEqual(4);
      const approved = published.filter((item: { keyword: string }) => item.keyword === keyword);
      expect(rankExpressionAssets(apk.templates, keyword).slice(0, approved.length).map(item => item.id))
        .toEqual(approved.map((item: { id: string }) => item.id));
    }
  }, 30000);
});
