import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
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
  async function prebuiltFixture() {
    const root = await mkdtemp(join(tmpdir(), 'expression-independent-gif-'));
    temporaryRoots.push(root);
    const sourceRoot = join(root, 'source');
    const outputRoot = join(root, 'output');
    const androidAssetsRoot = join(root, 'android');
    await mkdir(sourceRoot);
    await mkdir(outputRoot);
    await mkdir(androidAssetsRoot);
    await writeFile(join(outputRoot, 'sentinel'), 'preserve');
    await writeFile(join(androidAssetsRoot, 'sentinel'), 'preserve');
    const bytes = await readFile(new URL('../../../artifacts/expression-prototypes/gifs/thanks-nuotuan-bow.gif', import.meta.url));
    await writeFile(join(sourceRoot, 'approved.gif'), bytes);
    const asset = {
      id: 'approved-thanks', source: 'approved.gif',
      sha256: createHash('sha256').update(bytes).digest('hex'),
      embeddedText: '谢谢', keywords: ['谢谢', '感谢'], emotions: ['happy'],
      style: 'original-character', sourceType: 'ai-original',
      provenance: { manifest: 'prototypes/manifest.json', itemId: 'thanks-nuotuan-bow' },
    };
    const manifest = {
      version: 'independent-v1',
      expectedCounts: { templates: 0, animatedTemplates: 0, emojiBases: 0 },
      templates: [], emojiBases: [], prebuiltAssets: [asset],
    };
    const options = { manifestPath: join(sourceRoot, 'manifest.json'), sourceRoot, outputRoot, androidAssetsRoot };
    return { root, bytes, asset, manifest, options };
  }

  async function nativeTemplateFixture(sourceType = 'ai-original') {
    const base = await prebuiltFixture();
    const bytes = await readFile(new URL('../../../artifacts/expression-character-trials/ai-synthesis-blank-01/output/cat-side-eye.gif', import.meta.url));
    await writeFile(join(base.options.sourceRoot, 'approved.gif'), bytes);
    await writeFile(join(base.options.sourceRoot, 'approval.json'), JSON.stringify({
      status: 'approved', approvedIds: ['blank-approved'], licensedIds: ['blank-approved'],
      approvedAssets: [{ id: 'blank-approved', sourceType, sha256: createHash('sha256').update(bytes).digest('hex') }],
    }));
    const template = {
      id: 'blank-approved', type: 'gif', source: 'approved.gif', keywords: ['嫌弃'], emotions: ['skeptical'],
      textSafeArea: { x: 6, y: 188, width: 228, height: 46 },
      layout: { minFontSize: 14, maxFontSize: 24, textColor: '#222222', strokeColor: '#ffffff', strokeWidth: 2, alignment: 'center', maxLines: 2 },
      animation: { sha256: createHash('sha256').update(bytes).digest('hex'), sourceType,
        provenance: { manifest: 'source.json', itemId: 'cat-side-eye', approvalRecord: 'approval.json' } },
    };
    const manifest = { version: 'native-v1', expectedCounts: { templates: 1, animatedTemplates: 1, emojiBases: 0 },
      templates: [template], emojiBases: [], builtInTemplateIds: [template.id] };
    await writeFile(base.options.manifestPath, JSON.stringify(manifest));
    return { ...base, bytes, template, manifest };
  }

  it.each(['ai-original', 'licensed'])('已批准%s无字动作模板原字节复制且保留20帧四秒', async sourceType => {
    const { bytes, options } = await nativeTemplateFixture(sourceType);
    const catalog = await generateExpressionAssets(options);
    const item = catalog.templates[0];
    expect(item).toMatchObject({ type: 'synthesis-template', format: 'gif', width: 240, height: 240,
      embeddedText: null, sourceType, textSafeArea: { x: 6, y: 188, width: 228, height: 46 } });
    expect(await readFile(join(options.outputRoot, item.fileName))).toEqual(bytes);
    expect(await readFile(join(options.androidAssetsRoot, item.fileName))).toEqual(bytes);
    const meta = await sharp(await readFile(join(options.outputRoot, item.fileName)), { animated: true }).metadata();
    expect(meta.pages).toBe(20);
    expect(meta.delay?.reduce((a, b) => a + b, 0)).toBe(4000);
  });

  it.each(['sha', 'sourceType', 'approval', 'path', 'layout', 'area', 'static', 'replacement', 'sourceTypeSwap'])('动作模板%s无效时预检失败且旧输出不被删除', async invalid => {
    const { template, manifest, options } = await nativeTemplateFixture('licensed');
    if (invalid === 'sha') template.animation.sha256 = '0'.repeat(64);
    if (invalid === 'sourceType') template.animation.sourceType = 'user-provided-reference';
    if (invalid === 'approval') await writeFile(join(options.sourceRoot, 'approval.json'), JSON.stringify({ status: 'pending', approvedIds: [], licensedIds: [] }));
    if (invalid === 'path') template.source = '../outside.gif';
    if (invalid === 'layout') template.layout.minFontSize = 0;
    if (invalid === 'area') template.textSafeArea.width = 999;
    if (invalid === 'static') template.type = 'static';
    if (invalid === 'replacement') {
      const replacement = await readFile(new URL('../../../artifacts/expression-character-trials/ai-synthesis-blank-01/output/man-snicker.gif', import.meta.url));
      await writeFile(join(options.sourceRoot, 'approved.gif'), replacement);
      template.animation.sha256 = createHash('sha256').update(replacement).digest('hex');
    }
    if (invalid === 'sourceTypeSwap') template.animation.sourceType = 'ai-original';
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    await expect(generateExpressionAssets(options)).rejects.toThrow();
    expect(await readFile(join(options.outputRoot, 'sentinel'), 'utf8')).toBe('preserve');
    expect(await readFile(join(options.androidAssetsRoot, 'sentinel'), 'utf8')).toBe('preserve');
  });

  it('全部批准的184张预制GIF按词最多内置4张且保持原字节', async () => {
    const { options } = await prebuiltFixture();
    const sourceRoot = new URL('../../../assets/expression/', import.meta.url).pathname;
    const manifest = JSON.parse(await readFile(join(sourceRoot, 'manifest.source.json'), 'utf8'));
    const independent = manifest.prebuiltAssets;
    expect(manifest.prebuiltPhrases.map((phrase: { text: string; idPrefix: string }) => [phrase.text, phrase.idPrefix])).toEqual([
      ['谢谢', 'prebuilt-02'], ['加油', 'prebuilt-05'], ['可以', 'prebuilt-07'],
      ['抱歉', 'prebuilt-08'], ['再见', 'prebuilt-10'], ['开心', 'prebuilt-11'],
      ['哈哈', 'prebuilt-12'], ['喜欢', 'prebuilt-13'], ['爱你', 'prebuilt-14'],
      ['生气', 'prebuilt-15'], ['不要', 'prebuilt-16'], ['快点', 'prebuilt-17'],
      ['无语', 'prebuilt-18'], ['收到', 'prebuilt-19'], ['在吗', 'prebuilt-20'],
    ]);
    expect(independent).toHaveLength(184);
    const archive = JSON.parse(await readFile(new URL('../../../server/images/index.json', import.meta.url), 'utf8'));
    expect(new Set(independent.map((item: { id: string }) => item.id)))
      .toEqual(new Set(archive.items.filter((item: { status: string }) => item.status === 'published').map((item: { id: string }) => item.id)));
    for (const word of new Set(independent.map((item: { embeddedText: string }) => item.embeddedText))) {
      const items = independent.filter((item: { embeddedText: string }) => item.embeddedText === word);
      expect(items.filter((item: { distribution?: string }) => item.distribution !== 'remote'))
        .toHaveLength(Math.min(4, items.length));
    }
    for (const word of ['你好', '早安', '晚安', '好的', '对不起']) {
      const items = independent.filter((item: { embeddedText: string }) => item.embeddedText === word);
      expect(items).toHaveLength(8);
      expect(manifest.prebuiltPhrases.some((phrase: { text: string }) => phrase.text === word)).toBe(false);
      expect(items.map((item: { distribution: string }) => item.distribution)).toEqual([
        ...Array(4).fill('bundled'), ...Array(4).fill('remote'),
      ]);
    }
    for (const word of ['谢谢', '无语', '笑死']) {
      const items = independent.filter((item: { embeddedText: string }) => item.embeddedText === word);
      expect(items).toHaveLength(4);
      expect(new Set(items.map((item: { style: string }) => item.style)).size).toBe(4);
    }
    // Keep this focused fixture cheap: the full chain separately audits legacy templates/bases.
    await writeFile(options.manifestPath, JSON.stringify({
      ...manifest, expectedCounts: { templates: 0, animatedTemplates: 0, emojiBases: 0 },
      templates: [], emojiBases: [], prebuiltPhrases: [], builtInTemplateIds: [], highFrequencyCombinations: [],
    }));
    const catalog = await generateExpressionAssets({ ...options, sourceRoot });
    expect(JSON.parse(await readFile(join(options.androidAssetsRoot, 'catalog.json'), 'utf8'))).toEqual(catalog);
    expect(catalog.templates.map((item) => item.id)).toEqual(independent.map((item: { id: string }) => item.id));
    for (const item of catalog.templates) {
      const source = independent.find((candidate: { id: string }) => candidate.id === item.id);
      const approved = await readFile(join(sourceRoot, source.source));
      expect((await readFile(join(options.outputRoot, item.fileName))).equals(approved)).toBe(true);
      if (source.distribution === 'remote') {
        await expect(readFile(join(options.androidAssetsRoot, item.fileName))).rejects.toMatchObject({ code: 'ENOENT' });
      } else {
        expect((await readFile(join(options.androidAssetsRoot, item.fileName))).equals(approved)).toBe(true);
      }
    }
  });

  it('拒绝同词第五张内置原GIF并保留旧产物', async () => {
    const { asset, manifest, options } = await prebuiltFixture();
    manifest.prebuiltAssets = Array.from({ length: 5 }, (_, index) => ({ ...asset, id: `copy-${index}` }));
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    await expect(generateExpressionAssets(options)).rejects.toThrow(/每词.*4/);
    expect(await readFile(join(options.androidAssetsRoot, 'sentinel'), 'utf8')).toBe('preserve');
  });

  it.each([4000, 4200])('已批准慢节奏质量边界：%i ms', async duration => {
    const { asset, manifest, options } = await prebuiltFixture();
    const source = await readFile(new URL('../../../artifacts/expression-batches/scene-rich-10/gifs/scene-rich-10-puzzled.gif', import.meta.url));
    const bytes = duration === 4000 ? source : await sharp(source, { animated: true })
      .gif({ loop: 0, delay: Array(20).fill(210), keepDuplicateFrames: true }).toBuffer();
    await writeFile(join(options.sourceRoot, 'approved.gif'), bytes);
    asset.sha256 = createHash('sha256').update(bytes).digest('hex');
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    if (duration === 4000) {
      const catalog = await generateExpressionAssets(options);
      expect(await readFile(join(options.androidAssetsRoot, catalog.templates[0].fileName))).toEqual(bytes);
    } else {
      await expect(generateExpressionAssets(options)).rejects.toThrow(/质量/);
    }
  });

  it('原样接入独立预制 GIF 并内置到 Android，缩略图是首帧无损 WebP', async () => {
    const { bytes, asset, manifest, options } = await prebuiltFixture();
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    const catalog = await generateExpressionAssets(options);
    expect(catalog.templates).toHaveLength(1);
    const item = catalog.templates[0];
    expect(item).toMatchObject({
      id: asset.id, type: 'prebuilt', format: 'gif', embeddedText: '谢谢', distribution: 'bundled',
      width: 240, height: 240, sha256: asset.sha256, layout: null, textSafeArea: null,
    });
    expect(await readFile(join(options.outputRoot, item.fileName))).toEqual(bytes);
    expect(await readFile(join(options.androidAssetsRoot, item.fileName))).toEqual(bytes);
    const thumbnail = join(options.outputRoot, item.thumbnailFileName!);
    expect(await sharp(thumbnail).metadata()).toMatchObject({ format: 'webp', width: 240, height: 240 });
    expect(await sharp(thumbnail).ensureAlpha().raw().toBuffer())
      .toEqual(await sharp(bytes, { page: 0, pages: 1 }).ensureAlpha().raw().toBuffer());
    expect(await readFile(join(options.androidAssetsRoot, item.thumbnailFileName!)))
      .toEqual(await readFile(thumbnail));
    expect(JSON.parse(await readFile(join(options.androidAssetsRoot, 'catalog.json'), 'utf8'))).toEqual(catalog);
  });

  it.each(['ai-original', 'cc0', 'public-domain', 'licensed'])('预制素材将已审计来源 %s 保留到 runtime 和 Android 索引', async sourceType => {
    const { asset, manifest, options } = await prebuiltFixture();
    Object.assign(asset, { sourceType, distribution: 'remote' });
    if (sourceType !== 'ai-original') Object.assign(asset.provenance, {
      sourceUrl: 'https://example.org/approved.gif', license: sourceType,
    });
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    const catalog = await generateExpressionAssets(options);
    expect(catalog.templates[0]).toMatchObject({ sourceType, sha256: asset.sha256 });
    for (const root of [options.outputRoot, options.androidAssetsRoot]) {
      const persisted = JSON.parse(await readFile(join(root, 'catalog.json'), 'utf8'));
      expect(persisted.templates[0]).toMatchObject({ sourceType, sha256: asset.sha256 });
    }
  });

  it('remote 仅服务端保留原 GIF，Android 保留首帧与完整索引', async () => {
    const { bytes, asset, manifest, options } = await prebuiltFixture();
    Object.assign(asset, { distribution: 'remote' });
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    const catalog = await generateExpressionAssets(options);
    const item = catalog.templates[0];
    expect(item).toMatchObject({ distribution: 'remote', type: 'prebuilt', format: 'gif' });
    expect(await readFile(join(options.outputRoot, item.fileName))).toEqual(bytes);
    await expect(readFile(join(options.androidAssetsRoot, item.fileName))).rejects.toMatchObject({ code: 'ENOENT' });
    expect(await readFile(join(options.androidAssetsRoot, item.thumbnailFileName!)))
      .toEqual(await readFile(join(options.outputRoot, item.thumbnailFileName!)));
    expect(JSON.parse(await readFile(join(options.androidAssetsRoot, 'catalog.json'), 'utf8'))).toEqual(catalog);
  });

  it.each([
    ['非法分发', { distribution: 'other' }, /分发/],
    ['空分发', { distribution: null }, /分发/],
    ['非法 ID', { id: '../escape' }, /ID 非法/],
    ['路径逃逸', { source: '../approved.gif' }, /路径/],
    ['绝对路径', { source: '/tmp/approved.gif' }, /路径/],
    ['哈希不符', { sha256: '0'.repeat(64) }, /SHA-256/],
    ['不支持的来源', { sourceType: 'unknown' }, /来源/],
    ['外部素材无许可', { sourceType: 'licensed' }, /许可/],
    ['空文字', { embeddedText: '' }, /文字/],
    ['无风格', { style: '' }, /风格/],
  ])('拒绝%s并保留现有产物', async (_label, patch, error) => {
    const { asset, manifest, options } = await prebuiltFixture();
    Object.assign(asset, patch);
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    await expect(generateExpressionAssets(options)).rejects.toThrow(error);
    expect(await readFile(join(options.outputRoot, 'sentinel'), 'utf8')).toBe('preserve');
    expect(await readFile(join(options.androidAssetsRoot, 'sentinel'), 'utf8')).toBe('preserve');
  });

  it.each(['../escape', '', null, 'bad__prefix'])('拒绝非法短语稳定前缀 %s 且保留产物', async (idPrefix) => {
    const { manifest, options } = await prebuiltFixture();
    await writeFile(options.manifestPath, JSON.stringify({
      ...manifest, prebuiltPhrases: [{ text: 'test', templateIds: [], idPrefix }],
    }));
    await expect(generateExpressionAssets(options)).rejects.toThrow(/ID 前缀非法/);
    expect(await readFile(join(options.outputRoot, 'sentinel'), 'utf8')).toBe('preserve');
    expect(await readFile(join(options.androidAssetsRoot, 'sentinel'), 'utf8')).toBe('preserve');
  });

  it('短语稳定前缀仍受全素材唯一 ID 校验', async () => {
    const { manifest, options } = await prebuiltFixture();
    await writeFile(options.manifestPath, JSON.stringify({
      ...manifest, prebuiltPhrases: [
        { text: 'one', templateIds: ['template'], idPrefix: 'prebuilt-42' },
        { text: 'two', templateIds: ['template'], idPrefix: 'prebuilt-42' },
      ],
    }));
    await expect(generateExpressionAssets(options)).rejects.toThrow(/ID 重复/);
    expect(await readFile(join(options.outputRoot, 'sentinel'), 'utf8')).toBe('preserve');
  });

  it('拒绝独立素材重复 ID', async () => {
    const { asset, manifest, options } = await prebuiltFixture();
    manifest.prebuiltAssets.push({ ...asset });
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    await expect(generateExpressionAssets(options)).rejects.toThrow(/ID 重复/);
  });

  it('拒绝经符号链接逃出源目录', async () => {
    const { root, bytes, asset, manifest, options } = await prebuiltFixture();
    await writeFile(join(root, 'outside.gif'), bytes);
    await symlink(join(root, 'outside.gif'), join(options.sourceRoot, 'link.gif'));
    asset.source = 'link.gif';
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    await expect(generateExpressionAssets(options)).rejects.toThrow(/路径/);
    expect(await readFile(join(options.outputRoot, 'sentinel'), 'utf8')).toBe('preserve');
  });

  it.each(['dimensions', 'frames', 'duration', 'loop', 'size', 'format'])('拒绝 GIF 质量不合格：%s', async (kind) => {
    const { bytes, asset, manifest, options } = await prebuiltFixture();
    let invalid: Buffer;
    if (kind === 'size') invalid = Buffer.concat([bytes, Buffer.alloc(250 * 1024)]);
    else if (kind === 'format') invalid = await sharp(bytes).png().toBuffer();
    else if (kind === 'frames') invalid = await sharp(bytes).gif({ loop: 0, delay: 1000 }).toBuffer();
    else {
      let image = sharp(bytes, { animated: true });
      if (kind === 'dimensions') image = image.resize(120, 120);
      invalid = await image.gif({ loop: kind === 'loop' ? 1 : 0, delay: kind === 'duration' ? 300 : 90 }).toBuffer();
    }
    await writeFile(join(options.sourceRoot, asset.source), invalid);
    asset.sha256 = createHash('sha256').update(invalid).digest('hex');
    await writeFile(options.manifestPath, JSON.stringify(manifest));
    await expect(generateExpressionAssets(options)).rejects.toThrow(/GIF 质量/);
    expect(await readFile(join(options.outputRoot, 'sentinel'), 'utf8')).toBe('preserve');
  });

  it.each([undefined, 'prebuilt-42'])('为中文短语生成四张含完整文字的预制图并保持稳定前缀 %s', async (idPrefix) => {
    const root = await mkdtemp(join(tmpdir(), 'expression-generator-prebuilt-'));
    temporaryRoots.push(root);
    const sourceRoot = join(root, 'source');
    const outputRoot = join(root, 'output');
    await mkdir(join(sourceRoot, 'templates'), { recursive: true });
    const templateIds = ['one', 'two', 'three', 'four'];
    for (const [index, id] of templateIds.entries()) {
      await createSourceImage(
        join(sourceRoot, 'templates', `${id}.png`),
        ['#ef5350', '#42a5f5', '#66bb6a', '#ab47bc'][index],
      );
    }
    const textSafeArea = { x: 32, y: 32, width: 448, height: 160 };
    const layout = {
      minFontSize: 24, maxFontSize: 52, textColor: '#ffffff',
      strokeColor: '#000000', strokeWidth: 3, alignment: 'center', maxLines: 2,
    };
    const manifestPath = join(sourceRoot, 'manifest.source.json');
    await writeFile(manifestPath, JSON.stringify({
      version: 'prebuilt-v1',
      expectedCounts: { templates: 4, animatedTemplates: 0, emojiBases: 0 },
      prebuiltPhrases: [{ text: '你好', aliases: ['您好'], templateIds, idPrefix }],
      templates: templateIds.map((id) => ({
        id, type: 'static', source: `templates/${id}.png`,
        keywords: [], emotions: [], textSafeArea, layout,
      })),
      emojiBases: [],
    }));

    const catalog = await generateExpressionAssets({ manifestPath, sourceRoot, outputRoot });
    const prebuilt = catalog.templates.filter((item) => (
      item.type === 'prebuilt' && item.embeddedText === '你好'
    ));

    expect(prebuilt).toHaveLength(4);
    expect(catalog.templates.every(item => !Object.hasOwn(item, 'sourceType'))).toBe(true);
    expect(prebuilt.map((item) => item.id)).toEqual(templateIds.map((id) => `${idPrefix ?? 'prebuilt-01'}-${id}`));
    for (const item of prebuilt) {
      const sourceId = item.id.replace(/^prebuilt-\d+-/, '');
      const base = catalog.templates.find((candidate) => candidate.id === sourceId)!;
      const region = {
        left: textSafeArea.x,
        top: textSafeArea.y,
        width: textSafeArea.width,
        height: textSafeArea.height,
      };
      const [basePixels, prebuiltPixels] = await Promise.all([
        sharp(join(outputRoot, base.fileName)).extract(region).raw().toBuffer(),
        sharp(join(outputRoot, item.fileName)).extract(region).raw().toBuffer(),
      ]);
      expect(prebuiltPixels.equals(basePixels)).toBe(false);
    }
  });

  it('按源裁剪框去掉顶部空白后再铺满模板画面', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-generator-crop-'));
    temporaryRoots.push(root);
    const sourceRoot = join(root, 'source');
    const outputRoot = join(root, 'output');
    await mkdir(join(sourceRoot, 'templates'), { recursive: true });
    const sourcePath = join(sourceRoot, 'templates', 'cropped.png');
    await sharp({
      create: {
        width: 320,
        height: 320,
        channels: 3,
        background: '#ffffff',
      },
    }).composite([{
      input: await sharp({
        create: {
          width: 320,
          height: 160,
          channels: 3,
          background: '#e53935',
        },
      }).png().toBuffer(),
      left: 0,
      top: 160,
    }]).png().toFile(sourcePath);
    const manifestPath = join(sourceRoot, 'manifest.source.json');
    await writeFile(manifestPath, JSON.stringify({
      version: 'crop-v1',
      expectedCounts: { templates: 1, animatedTemplates: 0, emojiBases: 0 },
      templates: [{
        id: 'cropped', type: 'static', source: 'templates/cropped.png',
        keywords: ['裁剪'], emotions: [],
        sourceCrop: { x: 0, y: 160, width: 320, height: 160 },
        textSafeArea: { x: 32, y: 32, width: 448, height: 128 },
        layout: {
          minFontSize: 24, maxFontSize: 48, textColor: '#ffffff',
          strokeColor: '#000000', strokeWidth: 2, alignment: 'center', maxLines: 2,
        },
      }],
      emojiBases: [],
    }));

    const catalog = await generateExpressionAssets({ manifestPath, sourceRoot, outputRoot });
    const { data, info } = await sharp(join(outputRoot, catalog.templates[0].fileName))
      .raw()
      .toBuffer({ resolveWithObject: true });

    expect(info).toMatchObject({ width: 512, height: 512, channels: 3 });
    const [red, green, blue] = data.subarray(0, 3);
    expect(red).toBeGreaterThan(200);
    expect(green).toBeLessThan(80);
    expect(blue).toBeLessThan(80);
  });

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
