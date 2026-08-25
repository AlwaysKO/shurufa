import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import sharp from 'sharp';
import { afterEach, describe, expect, it } from 'vitest';
import { cropExpressionContactSheet, generateExpressionAssets } from './assetGenerator.js';

const temporaryRoots: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => (
    rm(root, { recursive: true, force: true })
  )));
});

async function createSourceImage(path: string, color: string): Promise<void> {
  await sharp({
    create: {
      width: 320,
      height: 320,
      channels: 4,
      background: color,
    },
  }).png().toFile(path);
}

describe('generateExpressionAssets', () => {
  it('生成模板、基础表情和完整有序组合并复制 Android 内置子集', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-generator-'));
    temporaryRoots.push(root);
    const sourceRoot = join(root, 'source');
    const outputRoot = join(root, 'output');
    const androidAssetsRoot = join(root, 'android');
    await mkdir(join(sourceRoot, 'templates'), { recursive: true });
    await mkdir(join(sourceRoot, 'emoji-base'), { recursive: true });
    await createSourceImage(join(sourceRoot, 'templates', 'static.png'), '#ff8844');
    await createSourceImage(join(sourceRoot, 'templates', 'animated.png'), '#4488ff');
    await createSourceImage(join(sourceRoot, 'emoji-base', 'smile.png'), '#ffd54f');
    await createSourceImage(join(sourceRoot, 'emoji-base', 'cry.png'), '#64b5f6');
    const manifestPath = join(sourceRoot, 'manifest.source.json');
    await writeFile(manifestPath, JSON.stringify({
      version: 'test-v1',
      expectedCounts: { templates: 2, animatedTemplates: 1, emojiBases: 2 },
      builtInTemplateIds: ['static'],
      highFrequencyCombinations: ['smile__cry'],
      templates: [
        {
          id: 'static', type: 'static', source: 'templates/static.png',
          keywords: ['夸奖'], emotions: ['happy'],
          textSafeArea: { x: 32, y: 32, width: 256, height: 96 },
          layout: {
            minFontSize: 24, maxFontSize: 48, textColor: '#ffffff',
            strokeColor: '#000000', strokeWidth: 2, alignment: 'center', maxLines: 2,
          },
        },
        {
          id: 'animated', type: 'gif', source: 'templates/animated.png',
          keywords: ['震惊'], emotions: ['surprised'],
          textSafeArea: { x: 32, y: 32, width: 256, height: 96 },
          layout: {
            minFontSize: 24, maxFontSize: 48, textColor: '#ffffff',
            strokeColor: '#000000', strokeWidth: 2, alignment: 'center', maxLines: 2,
          },
        },
      ],
      emojiBases: [
        { id: 'smile', name: '微笑', emotions: ['happy'], source: 'emoji-base/smile.png' },
        { id: 'cry', name: '哭泣', emotions: ['sad'], source: 'emoji-base/cry.png' },
      ],
    }, null, 2));

    const catalog = await generateExpressionAssets({
      manifestPath,
      sourceRoot,
      outputRoot,
      androidAssetsRoot,
    });

    expect(catalog.templates).toHaveLength(2);
    expect(catalog.emojiBases).toHaveLength(2);
    expect(catalog.emojiCombinations).toHaveLength(4);
    expect(new Set(catalog.emojiCombinations.map((item) => item.key)).size).toBe(4);

    const forward = catalog.emojiCombinations.find((item) => item.key === 'smile__cry')!;
    const reverse = catalog.emojiCombinations.find((item) => item.key === 'cry__smile')!;
    expect(forward.sha256).not.toBe(reverse.sha256);
    const metadata = await sharp(join(outputRoot, forward.fileName)).metadata();
    expect(metadata).toMatchObject({ format: 'webp', width: 256, height: 256 });
    const animatedTemplate = catalog.templates.find((item) => item.id === 'animated')!;
    const animatedMetadata = await sharp(
      join(outputRoot, animatedTemplate.fileName),
      { animated: true },
    ).metadata();
    expect(animatedMetadata).toMatchObject({ format: 'gif', pages: 4, pageHeight: 512 });
    expect(JSON.parse(await readFile(join(outputRoot, 'catalog.json'), 'utf8')))
      .toEqual(catalog);
    expect(await readFile(join(androidAssetsRoot, forward.fileName)))
      .toEqual(await readFile(join(outputRoot, forward.fileName)));
  });

  it('拒绝重复 ID 和与声明不符的数量', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-generator-invalid-'));
    temporaryRoots.push(root);
    const manifestPath = join(root, 'manifest.source.json');
    await writeFile(manifestPath, JSON.stringify({
      version: 'bad-v1',
      expectedCounts: { templates: 2, animatedTemplates: 0, emojiBases: 1 },
      templates: [
        { id: 'same', type: 'static', source: 'a.png', keywords: [], emotions: [] },
        { id: 'same', type: 'static', source: 'b.png', keywords: [], emotions: [] },
      ],
      emojiBases: [],
    }));

    await expect(generateExpressionAssets({
      manifestPath,
      sourceRoot: root,
      outputRoot: join(root, 'output'),
    })).rejects.toThrow(/重复|数量/);
  });

  it('拒绝内置清单引用未知模板或未知组合', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-generator-unknown-'));
    temporaryRoots.push(root);
    const manifestPath = join(root, 'manifest.source.json');
    await writeFile(manifestPath, JSON.stringify({
      version: 'bad-reference-v1',
      expectedCounts: { templates: 0, animatedTemplates: 0, emojiBases: 1 },
      builtInTemplateIds: ['missing-template'],
      highFrequencyCombinations: ['only__missing'],
      templates: [],
      emojiBases: [
        { id: 'only', name: '唯一', emotions: [], source: 'missing.png' },
      ],
    }));

    await expect(generateExpressionAssets({
      manifestPath,
      sourceRoot: root,
      outputRoot: join(root, 'output'),
    })).rejects.toThrow(/未知模板|未知组合/);
  });
});

describe('cropExpressionContactSheet', () => {
  it('按固定网格裁切并输出统一尺寸的透明 PNG', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-contact-sheet-'));
    temporaryRoots.push(root);
    const sourcePath = join(root, 'sheet.png');
    const outputRoot = join(root, 'cells');
    await sharp({
      create: {
        width: 512,
        height: 512,
        channels: 4,
        background: { r: 255, g: 0, b: 0, alpha: 0.5 },
      },
    }).png().toFile(sourcePath);

    const paths = await cropExpressionContactSheet({
      sourcePath,
      outputRoot,
      ids: Array.from({ length: 16 }, (_, index) => `cell-${index + 1}`),
      columns: 4,
    });

    expect(paths).toHaveLength(16);
    expect(await sharp(paths[0]).metadata()).toMatchObject({
      format: 'png', width: 128, height: 128, hasAlpha: true,
    });
  });

  it('先规范化不能整除的联系表再按目标单元尺寸裁切', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-contact-sheet-resize-'));
    temporaryRoots.push(root);
    const sourcePath = join(root, 'sheet.png');
    await sharp({
      create: {
        width: 510,
        height: 510,
        channels: 3,
        background: '#336699',
      },
    }).png().toFile(sourcePath);

    const paths = await cropExpressionContactSheet({
      sourcePath,
      outputRoot: join(root, 'cells'),
      ids: Array.from({ length: 16 }, (_, index) => `resized-${index + 1}`),
      columns: 4,
      rows: 4,
      cellSize: 128,
    });

    expect(await sharp(paths[15]).metadata()).toMatchObject({ width: 128, height: 128 });
  });
});
