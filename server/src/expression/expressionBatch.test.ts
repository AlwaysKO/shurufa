import { mkdtemp, readFile, mkdir, writeFile, rm } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import sharp from 'sharp';
import { describe, it, expect } from 'vitest';
import { validateExpressionBatchManifest, splitBatchMaster, renderExpressionBatch, resolveExpressionBatchPaths } from './expressionBatch.js';

const secondKeywords = ['哈哈', '加油', '收到', '可以', '再见'];
const actionKeywords = ['打闹', '追赶'];
const thirdKeywords = ['开心', '难过', '生气', '震惊', '抱抱'];
function fixture(keywords = ['你好', '早安', '晚安', '好的', '对不起']) {
  return { version: 'test', items: keywords.flatMap((keyword, k) =>
    Array.from({ length: 8 }, (_, i) => ({ id: `item-${k}-${i}`, keyword, text: keyword,
      style: 'original-character', direction: 'core-performance', sourceType: 'ai-original',
      prompt: '原创，无文字，无水印，无品牌，无现有角色', motionPreset: 'bow',
      frameCount: 16, durationMs: 1600, textPlacement: 'bottom',
      masterFile: `masters/item-${k}-${i}.png`,
      poseFiles: [1, 2, 3, 4].map(n => `poses/item-${k}-${i}/pose-0${n}.png`),
      distribution: i < 4 ? 'bundled' : 'remote', motionScript: ['站立', '抬手', '挥手', '放下'],
      generation: { provider: 'fixture' },
    }))) };
}
function actionFixture() {
  const value = fixture(actionKeywords);
  return { ...value, items: value.items.filter(item => item.distribution === 'bundled') };
}
async function master() {
  const layers = await Promise.all(['red', 'green', 'blue', 'yellow'].map((fill, i) => sharp(Buffer.from(
    `<svg width="120" height="120"><rect x="${20 + i * 5}" y="20" width="${30 + i * 6}" height="60" fill="${fill}"/></svg>`)).png().toBuffer()));
  return sharp({ create: { width: 240, height: 240, channels: 4, background: '#00000000' } })
    .composite(layers.map((input, i) => ({ input, left: i % 2 * 120, top: Math.floor(i / 2) * 120 }))).png().toBuffer();
}
describe('daily batch manifest', () => {
  it('accepts 40 new-keyword items and preserves distribution and generation', () => {
    const result = validateExpressionBatchManifest(fixture());
    expect(result.items).toHaveLength(40);
    expect(result.items[0].keyword).toBe('你好');
    expect(result.items[0].generation).toEqual({ provider: 'fixture' });
  });
  it('accepts only the selected second batch keywords without changing the default', () => {
    expect(validateExpressionBatchManifest(fixture(secondKeywords), 'daily-02').items).toHaveLength(40);
    expect(() => validateExpressionBatchManifest(fixture(secondKeywords))).toThrow(/keyword/);
    expect(() => validateExpressionBatchManifest(fixture(), 'daily-02')).toThrow(/keyword/);
  });
  it.each(['count', 'distribution', 'mixed'])('rejects invalid second batch %s', kind => {
    const f = fixture(secondKeywords);
    if (kind === 'count') f.items.pop();
    if (kind === 'distribution') f.items[0].distribution = 'remote';
    if (kind === 'mixed') { f.items[0].keyword = '你好'; f.items[0].text = '你好'; }
    expect(() => validateExpressionBatchManifest(f, 'daily-02')).toThrow();
  });
  it('accepts only the selected third batch keywords while keeping earlier batches isolated', () => {
    const result = validateExpressionBatchManifest(fixture(thirdKeywords), 'daily-03');
    expect(result.items).toHaveLength(40);
    expect([...new Set(result.items.map(item => item.keyword))]).toEqual(thirdKeywords);
    for (const keyword of thirdKeywords) for (const distribution of ['bundled', 'remote']) {
      expect(result.items.filter(item => item.keyword === keyword && item.distribution === distribution)).toHaveLength(4);
    }
    expect(() => validateExpressionBatchManifest(fixture(thirdKeywords))).toThrow(/keyword/);
    expect(() => validateExpressionBatchManifest(fixture(thirdKeywords), 'daily-02')).toThrow(/keyword/);
    expect(() => validateExpressionBatchManifest(fixture(), 'daily-03')).toThrow(/keyword/);
    expect(() => validateExpressionBatchManifest(fixture(secondKeywords), 'daily-03')).toThrow(/keyword/);
  });
  it.each(['count', 'distribution', 'mixed'])('rejects invalid third batch %s', kind => {
    const f = fixture(thirdKeywords);
    if (kind === 'count') f.items.pop();
    if (kind === 'distribution') f.items[0].distribution = 'remote';
    if (kind === 'mixed') { f.items[0].keyword = '哈哈'; f.items[0].text = '哈哈'; }
    expect(() => validateExpressionBatchManifest(f, 'daily-03'))
      .toThrow(kind === 'count' ? /40/ : kind === 'distribution' ? /4 bundled \+ 4 remote/ : /keyword/);
  });
  it('accepts action-01 with exactly four bundled items per action and no remote items', () => {
    const result = validateExpressionBatchManifest(actionFixture(), 'action-01');
    expect(result.items).toHaveLength(8);
    for (const keyword of actionKeywords) {
      expect(result.items.filter(item => item.keyword === keyword && item.distribution === 'bundled')).toHaveLength(4);
    }
    expect(result.items.every(item => item.distribution === 'bundled')).toBe(true);
  });
  it.each(['count', 'remote', 'unbalanced', 'mixed'])('rejects invalid action batch %s', kind => {
    const value = actionFixture();
    if (kind === 'count') value.items.pop();
    if (kind === 'remote') value.items[0].distribution = 'remote';
    if (kind === 'unbalanced') { value.items[0].keyword = '追赶'; value.items[0].text = '追赶'; }
    if (kind === 'mixed') { value.items[0].keyword = '谢谢'; value.items[0].text = '谢谢'; }
    expect(() => validateExpressionBatchManifest(value, 'action-01'))
      .toThrow(kind === 'count' ? /8/ : kind === 'mixed' ? /keyword/ : /4 bundled \+ 0 remote/);
  });
  it('accepts semantic-01 with only four bundled 懂了 items and isolated paths', () => {
    const value = fixture(['懂了']);
    value.items = value.items.filter(item => item.distribution === 'bundled');
    expect(validateExpressionBatchManifest(value, 'semantic-01').items).toHaveLength(4);
    expect(resolveExpressionBatchPaths('/project', 'semantic-01')).toEqual({
      batch: 'semantic-01', sourceRoot: '/project/assets/expression/batches/semantic-01',
      outputRoot: '/project/artifacts/expression-batches/semantic-01',
    });
    for (const batch of ['daily-01', 'daily-02', 'daily-03', 'action-01']) {
      expect(() => validateExpressionBatchManifest(value, batch)).toThrow(/恰好/);
    }
  });
  it.each(['count', 'remote', 'mixed', 'daily-allocation'])('rejects invalid semantic batch %s', kind => {
    const value = fixture(['懂了']);
    if (kind !== 'daily-allocation') value.items = value.items.filter(item => item.distribution === 'bundled');
    if (kind === 'count') value.items.pop();
    if (kind === 'remote') value.items[0].distribution = 'remote';
    if (kind === 'mixed') { value.items[0].keyword = '收到'; value.items[0].text = '收到'; }
    expect(() => validateExpressionBatchManifest(value, 'semantic-01'))
      .toThrow(kind === 'mixed' ? /keyword/ : kind === 'remote' ? /4 bundled \+ 0 remote/ : /恰好4项/);
  });
  it('does not weaken daily batch sizes or accept daily allocation for actions', () => {
    for (const batch of ['daily-01', 'daily-02', 'daily-03']) {
      expect(() => validateExpressionBatchManifest(actionFixture(), batch)).toThrow(/40/);
    }
    expect(() => validateExpressionBatchManifest(fixture(actionKeywords), 'action-01')).toThrow(/8/);
    expect(resolveExpressionBatchPaths('/project', 'action-01')).toEqual({
      batch: 'action-01', sourceRoot: '/project/assets/expression/batches/action-01',
      outputRoot: '/project/artifacts/expression-batches/action-01',
    });
  });
  it('rejects an unknown batch even with a valid default manifest', () => {
    expect(() => validateExpressionBatchManifest(fixture(), 'daily-04')).toThrow(/batch/);
  });
  it('resolves isolated source and output roots with a compatible default', () => {
    expect(resolveExpressionBatchPaths('/project')).toEqual({
      batch: 'daily-01', sourceRoot: '/project/assets/expression/batches/daily-01',
      outputRoot: '/project/artifacts/expression-batches/daily-01',
    });
    expect(resolveExpressionBatchPaths('/project', 'daily-02')).toEqual({
      batch: 'daily-02', sourceRoot: '/project/assets/expression/batches/daily-02',
      outputRoot: '/project/artifacts/expression-batches/daily-02',
    });
    expect(resolveExpressionBatchPaths('/project', 'daily-03')).toEqual({
      batch: 'daily-03', sourceRoot: '/project/assets/expression/batches/daily-03',
      outputRoot: '/project/artifacts/expression-batches/daily-03',
    });
    expect(() => resolveExpressionBatchPaths('/project', '../outside')).toThrow(/batch/);
  });
  it('CLI recognizes --batch and rejects unknown batches before reading assets', async () => {
    const script = fileURLToPath(new URL('../../scripts/render-expression-batch.ts', import.meta.url));
    await expect(promisify(execFile)(process.execPath, ['--import', 'tsx', script, '--batch=daily-04']))
      .rejects.toMatchObject({ stderr: expect.stringContaining('batch 不在白名单内') });
  });
  it('preserves explicit pose rectangles in the validated manifest', () => {
    const f = fixture();
    const poseRects = [
      { left: 0, top: 0, width: 90, height: 100 }, { left: 90, top: 0, width: 150, height: 100 },
      { left: 0, top: 100, width: 130, height: 140 }, { left: 130, top: 100, width: 110, height: 140 },
    ];
    const value = { ...f, items: f.items.map((item, i) => i === 0 ? { ...item, poseRects } : item) };
    expect(validateExpressionBatchManifest(value).items[0].poseRects).toEqual(poseRects);
  });
  it.each(['count', 'distribution', 'duplicate', 'path', 'source', 'poses', 'script', 'text', 'frames', 'duration', 'prompt'])('rejects invalid %s', kind => {
    const f = fixture();
    if (kind === 'count') f.items.pop();
    if (kind === 'distribution') f.items[0].distribution = 'remote';
    if (kind === 'duplicate') f.items[1].id = f.items[0].id;
    if (kind === 'path') f.items[0].masterFile = '../outside.png';
    if (kind === 'source') f.items[0].sourceType = 'licensed';
    if (kind === 'poses') f.items[0].poseFiles.pop();
    if (kind === 'script') f.items[0].motionScript = [''];
    if (kind === 'text') f.items[0].text = '再见';
    if (kind === 'frames') f.items[0].frameCount = 21;
    if (kind === 'duration') f.items[0].durationMs = 700;
    if (kind === 'prompt') f.items[0].prompt = '原创';
    expect(() => validateExpressionBatchManifest(f)).toThrow();
  });
});
describe('batch rendering', () => {
  it.each([
    { batch: 'daily-02', keywords: secondKeywords, expectedTotal: 40 },
    { batch: 'daily-03', keywords: thirdKeywords, expectedTotal: 40 },
    { batch: 'action-01', keywords: actionKeywords, expectedTotal: 8 },
    { batch: 'semantic-01', keywords: ['懂了'], expectedTotal: 4 },
  ])('renders $batch fixtures into isolated partial outputs and keyword contact sheets', async ({ batch, keywords, expectedTotal }) => {
    const root = await mkdtemp(join(tmpdir(), 'batch-'));
    try {
      const paths = resolveExpressionBatchPaths(root, batch);
      const previous = resolveExpressionBatchPaths(root);
      await mkdir(previous.outputRoot, { recursive: true });
      await writeFile(join(previous.outputRoot, 'sentinel'), 'keep daily-01');
      const other = resolveExpressionBatchPaths(root, batch === 'daily-03' ? 'daily-02' : 'daily-03');
      await mkdir(other.outputRoot, { recursive: true });
      await writeFile(join(other.outputRoot, 'sentinel'), 'keep other batch');
      await mkdir(join(paths.sourceRoot, 'masters'), { recursive: true });
      await writeFile(join(paths.sourceRoot, 'masters/item-0-0.png'), await master());
      const manifest = fixture(keywords);
      if (batch === 'action-01' || batch === 'semantic-01') manifest.items = manifest.items.filter(item => item.distribution === 'bundled');
      const result = await renderExpressionBatch({ ...paths, manifest, ids: ['item-0-0'] });
      expect(result.report).toMatchObject({ expectedTotal, total: 1, pass: 1, fail: 0, complete: false });
      expect(await readFile(join(result.outputRoot, 'preview.html'), 'utf8')).toContain(`1/${expectedTotal} 项`);
      expect(result.report.items[0].keyword).toBe(keywords[0]);
      expect(result.outputRoot.startsWith(join(paths.outputRoot, 'partial') + '/')).toBe(true);
      expect(await readFile(join(result.outputRoot, 'contact-sheet-1.webp'))).not.toHaveLength(0);
      expect(await readFile(join(previous.outputRoot, 'sentinel'), 'utf8')).toBe('keep daily-01');
      expect(await readFile(join(other.outputRoot, 'sentinel'), 'utf8')).toBe('keep other batch');
    } finally { await rm(root, { recursive: true, force: true }); }
  }, 30000);
  it('splits exactly four quadrants without dropping alpha or changing pixels', async () => {
    const source = await master();
    const poses = await splitBatchMaster(source);
    expect(poses).toHaveLength(4);
    for (let i = 0; i < 4; i++) {
      expect(await sharp(poses[i]).metadata()).toMatchObject({ width: 120, height: 120, hasAlpha: true });
      expect(await sharp(poses[i]).raw().toBuffer()).toEqual(await sharp(source).extract({ left: i % 2 * 120, top: Math.floor(i / 2) * 120, width: 120, height: 120 }).raw().toBuffer());
    }
  });
  it.each(['row', 'column'])('crops unequal %s rectangles and reconstructs every original RGBA pixel', async orientation => {
    const source = await master();
    const rectangles = [
      { left: 0, top: 0, width: 90, height: 100 }, { left: 90, top: 0, width: 150, height: 100 },
      { left: 0, top: 100, width: 130, height: 140 }, { left: 130, top: 100, width: 110, height: 140 },
    ];
    const orientedRects = orientation === 'row' ? rectangles : rectangles.map(rect => ({
      left: rect.top, top: rect.left, width: rect.height, height: rect.width,
    }));
    const poses = await splitBatchMaster(source, orientedRects);
    const reconstructed = Buffer.alloc(240 * 240 * 4);
    for (const [i, pose] of poses.entries()) {
      const { data, info } = await sharp(pose).raw().toBuffer({ resolveWithObject: true });
      const rect = orientedRects[i];
      expect(info).toMatchObject({ width: rect.width, height: rect.height });
      for (let y = 0; y < info.height; y++) data.copy(reconstructed, ((rect.top + y) * 240 + rect.left) * 4,
        y * info.width * 4, (y + 1) * info.width * 4);
    }
    expect(reconstructed.equals(await sharp(source).raw().toBuffer())).toBe(true);
  });
  it.each(['count', 'negative', 'zero', 'fractional', 'bounds', 'overlap', 'gap'])('rejects invalid explicit rectangle %s', async kind => {
    const rectangles = [
      { left: 0, top: 0, width: 120, height: 120 }, { left: 120, top: 0, width: 120, height: 120 },
      { left: 0, top: 120, width: 120, height: 120 }, { left: 120, top: 120, width: 120, height: 120 },
    ];
    if (kind === 'count') rectangles.pop();
    if (kind === 'negative') rectangles[0].left = -1;
    if (kind === 'zero') rectangles[0].width = 0;
    if (kind === 'fractional') rectangles[0].width = 120.5;
    if (kind === 'bounds') rectangles[3].width = 121;
    if (kind === 'overlap') { rectangles[0].width = 121; rectangles[3].width = 119; }
    if (kind === 'gap') rectangles[0].width = 119;
    await expect(splitBatchMaster(await master(), rectangles)).rejects.toThrow(/poseRects/);
  });
  it('rejects opaque masters', async () => {
    for (const width of [239, 240]) {
      const source = await sharp({ create: { width, height: 240, channels: 3, background: 'white' } }).png().toBuffer();
      await expect(splitBatchMaster(source)).rejects.toThrow();
    }
  });
  it('preserves every pixel of odd-sized RGBA masters with remainder pixels in the right and bottom quadrants', async () => {
    const source = await sharp(await master()).extend({ right: 1, bottom: 1, background: '#12345678' }).png().toBuffer();
    const poses = await splitBatchMaster(source);
    for (const [i, pose] of poses.entries()) {
      expect(await sharp(pose).metadata()).toMatchObject({ width: 120 + i % 2, height: 120 + Math.floor(i / 2), hasAlpha: true });
    }
    const reconstructed = Buffer.alloc(241 * 241 * 4);
    for (const [i, pose] of poses.entries()) {
      const { data, info } = await sharp(pose).raw().toBuffer({ resolveWithObject: true });
      for (let y = 0; y < info.height; y++) {
        data.copy(reconstructed, ((Math.floor(i / 2) * 120 + y) * 241 + i % 2 * 120) * 4,
          y * info.width * 4, (y + 1) * info.width * 4);
      }
    }
    expect(reconstructed.equals(await sharp(source).raw().toBuffer())).toBe(true);
  });
  it('rejects all-opaque RGBA, empty, and duplicated four-pose sheets', async () => {
    for (const background of ['#ffffffff', '#00000000']) {
      const source = await sharp({ create: { width: 240, height: 240, channels: 4, background } }).png().toBuffer();
      await expect(splitBatchMaster(source)).rejects.toThrow();
    }
    const pose = (await splitBatchMaster(await master()))[0];
    const repeated = await sharp({ create: { width: 240, height: 240, channels: 4, background: '#00000000' } })
      .composite([0, 1, 2, 3].map(i => ({ input: pose, left: i % 2 * 120, top: Math.floor(i / 2) * 120 }))).png().toBuffer();
    await expect(splitBatchMaster(repeated)).rejects.toThrow(/不同/);
  });
  it('fails explicitly on missing source, preserving existing outputs', async () => {
    const root = await mkdtemp(join(tmpdir(), 'batch-'));
    try {
      await mkdir(join(root, 'output')); await writeFile(join(root, 'output/sentinel'), 'keep');
      await expect(renderExpressionBatch({ manifest: fixture(), sourceRoot: root, outputRoot: join(root, 'output') })).rejects.toThrow(/item-0-0/);
      expect(await readFile(join(root, 'output/sentinel'), 'utf8')).toBe('keep');
    } finally { await rm(root, { recursive: true, force: true }); }
  });
  it('keeps existing output unchanged when an explicit crop exceeds source bounds', async () => {
    const root = await mkdtemp(join(tmpdir(), 'batch-'));
    try {
      await mkdir(join(root, 'masters')); await writeFile(join(root, 'masters/item-0-0.png'), await master());
      const options = { manifest: fixture(), sourceRoot: root, outputRoot: join(root, 'output'), ids: ['item-0-0'] };
      const previous = await renderExpressionBatch(options);
      await writeFile(join(previous.outputRoot, 'sentinel'), 'keep');
      const oldReport = await readFile(join(previous.outputRoot, 'report.json'));
      const invalid = { ...options.manifest, items: options.manifest.items.map((item, i) => i ? item : {
        ...item, poseRects: [0, 1, 2, 3].map(n => ({ left: n % 2 * 120, top: Math.floor(n / 2) * 120, width: 9999, height: 120 })),
      }) };
      await expect(renderExpressionBatch({ ...options, manifest: invalid })).rejects.toThrow(/item-0-0/);
      expect(await readFile(join(previous.outputRoot, 'sentinel'), 'utf8')).toBe('keep');
      expect(await readFile(join(previous.outputRoot, 'report.json'))).toEqual(oldReport);
    } finally { await rm(root, { recursive: true, force: true }); }
  }, 30000);
  it('keeps the previous publication unchanged when a pose destination is occupied by a regular file', async () => {
    const root = await mkdtemp(join(tmpdir(), 'batch-'));
    try {
      await mkdir(join(root, 'masters'));
      await writeFile(join(root, 'masters/item-0-0.png'), await master());
      const options = { manifest: fixture(), sourceRoot: root, outputRoot: join(root, 'output'), ids: ['item-0-0'] };
      const previous = await renderExpressionBatch(options);
      await writeFile(join(previous.outputRoot, 'sentinel'), 'previous publication');
      const oldReport = await readFile(join(previous.outputRoot, 'report.json'));
      await rm(join(root, 'poses/item-0-0'), { recursive: true });
      await writeFile(join(root, 'poses/item-0-0'), 'blocked destination');
      await expect(renderExpressionBatch(options)).rejects.toThrow();
      expect(await readFile(join(previous.outputRoot, 'sentinel'), 'utf8')).toBe('previous publication');
      expect(await readFile(join(previous.outputRoot, 'report.json'))).toEqual(oldReport);
      expect(await readFile(join(root, 'poses/item-0-0'), 'utf8')).toBe('blocked destination');
    } finally { await rm(root, { recursive: true, force: true }); }
  }, 30000);
  it('renders selected fixtures with honest partial counts, first-frame fallback and review labels', async () => {
    const root = await mkdtemp(join(tmpdir(), 'batch-'));
    try {
      await mkdir(join(root, 'masters')); await writeFile(join(root, 'masters/item-0-0.png'), await master());
      const result = await renderExpressionBatch({ manifest: fixture(), sourceRoot: root, outputRoot: join(root, 'output'), ids: ['item-0-0'] });
      expect(result.report).toMatchObject({ expectedTotal: 40, total: 1, pass: 1, fail: 0, complete: false, humanReview: 'pending' });
      expect(result.outputRoot).not.toBe(join(root, 'output'));
      const gif = await readFile(join(result.outputRoot, 'gifs/item-0-0.gif'));
      const thumb = await readFile(join(result.outputRoot, 'thumbnails/item-0-0.webp'));
      expect(await sharp(gif, { animated: true }).metadata()).toMatchObject({ format: 'gif', pages: 16, width: 240, loop: 0 });
      expect(await sharp(thumb).raw().toBuffer()).toEqual(await sharp(gif, { page: 0 }).raw().toBuffer());
      expect(await readFile(join(result.outputRoot, 'preview.html'), 'utf8')).toContain('内置');
      expect(await readFile(join(result.outputRoot, 'report.json'), 'utf8')).toContain('pending');
      await expect(renderExpressionBatch({ manifest: fixture(), sourceRoot: root, outputRoot: join(root, 'output'), ids: ['unknown'] })).rejects.toThrow(/unknown/);
    } finally { await rm(root, { recursive: true, force: true }); }
  }, 30000);
});
