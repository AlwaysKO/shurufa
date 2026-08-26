import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join, posix } from 'node:path';
import sharp from 'sharp';
import type {
  EmojiBase,
  EmojiCombination,
  ExpressionAsset,
  ExpressionTextLayout,
  ExpressionTextSafeArea,
} from '../types/expression.js';
import { emojiCombinationKey } from './catalog.js';

interface SourceTemplate {
  id: string;
  type: 'static' | 'gif';
  source: string;
  keywords: string[];
  emotions: string[];
  sourceCrop?: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
  textSafeArea: ExpressionTextSafeArea;
  layout: ExpressionTextLayout;
}

interface SourceEmojiBase {
  id: string;
  name: string;
  emotions: string[];
  source: string;
}

interface ExpressionSourceManifest {
  version: string;
  expectedCounts: {
    templates: number;
    animatedTemplates: number;
    emojiBases: number;
  };
  builtInTemplateIds?: string[];
  highFrequencyCombinations?: string[];
  templates: SourceTemplate[];
  emojiBases: SourceEmojiBase[];
}

export interface GeneratedExpressionCatalog {
  version: string;
  templates: ExpressionAsset[];
  emojiBases: EmojiBase[];
  emojiCombinations: EmojiCombination[];
}

export interface GenerateExpressionAssetsOptions {
  manifestPath: string;
  sourceRoot: string;
  outputRoot: string;
  androidAssetsRoot?: string;
}

export interface CropExpressionContactSheetOptions {
  sourcePath: string;
  outputRoot: string;
  ids: string[];
  columns: number;
  rows?: number;
  cellSize?: number;
}

const TEMPLATE_SIZE = 512;
const EMOJI_SIZE = 256;
const ID_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

async function sha256(path: string): Promise<string> {
  return createHash('sha256').update(await readFile(path)).digest('hex');
}

function assertUniqueIds(items: readonly { id: string }[], label: string): void {
  const ids = new Set<string>();
  for (const item of items) {
    if (!ID_PATTERN.test(item.id) || item.id.includes('__')) {
      throw new Error(`${label} ID 非法：${item.id}`);
    }
    if (ids.has(item.id)) throw new Error(`${label} ID 重复：${item.id}`);
    ids.add(item.id);
  }
}

function validateManifest(manifest: ExpressionSourceManifest): void {
  assertUniqueIds(manifest.templates, '模板');
  assertUniqueIds(manifest.emojiBases, '基础表情');
  const animatedTemplates = manifest.templates.filter((item) => item.type === 'gif').length;
  const expected = manifest.expectedCounts;
  if (
    manifest.templates.length !== expected.templates
    || animatedTemplates !== expected.animatedTemplates
    || manifest.emojiBases.length !== expected.emojiBases
  ) {
    throw new Error(
      `素材数量不符：templates=${manifest.templates.length}/${expected.templates}, `
      + `animated=${animatedTemplates}/${expected.animatedTemplates}, `
      + `emojiBases=${manifest.emojiBases.length}/${expected.emojiBases}`,
    );
  }
  const templateIds = new Set(manifest.templates.map(({ id }) => id));
  for (const id of manifest.builtInTemplateIds ?? []) {
    if (!templateIds.has(id)) throw new Error(`内置清单引用未知模板：${id}`);
  }
  const emojiIds = new Set(manifest.emojiBases.map(({ id }) => id));
  for (const key of manifest.highFrequencyCombinations ?? []) {
    const [firstId, secondId, extra] = key.split('__');
    if (extra !== undefined || !emojiIds.has(firstId) || !emojiIds.has(secondId)) {
      throw new Error(`内置清单引用未知组合：${key}`);
    }
  }
}

async function ensureParent(path: string): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
}

export async function cropExpressionContactSheet(
  options: CropExpressionContactSheetOptions,
): Promise<string[]> {
  const rows = options.rows ?? Math.ceil(options.ids.length / options.columns);
  if (options.columns <= 0 || rows <= 0 || options.ids.length > options.columns * rows) {
    throw new Error('联系表网格参数无效');
  }
  assertUniqueIds(options.ids.map((id) => ({ id })), '裁切素材');
  const metadata = await sharp(options.sourcePath).metadata();
  if (!metadata.width || !metadata.height) throw new Error('无法读取联系表尺寸');
  const mustNormalize = options.cellSize !== undefined;
  if (!mustNormalize && (
    metadata.width % options.columns !== 0 || metadata.height % rows !== 0
  )) {
    throw new Error('联系表尺寸不能被固定网格整除');
  }
  const cellWidth = options.cellSize ?? metadata.width / options.columns;
  const cellHeight = options.cellSize ?? metadata.height / rows;
  const normalizedInput = mustNormalize
    ? await sharp(options.sourcePath)
      .resize(options.columns * cellWidth, rows * cellHeight, { fit: 'fill' })
      .png()
      .toBuffer()
    : options.sourcePath;
  await mkdir(options.outputRoot, { recursive: true });
  const paths: string[] = [];
  for (const [index, id] of options.ids.entries()) {
    const targetPath = join(options.outputRoot, `${id}.png`);
    await sharp(normalizedInput)
      .extract({
        left: (index % options.columns) * cellWidth,
        top: Math.floor(index / options.columns) * cellHeight,
        width: cellWidth,
        height: cellHeight,
      })
      .png()
      .toFile(targetPath);
    paths.push(targetPath);
  }
  return paths;
}

async function prepareTemplateSource(
  sourcePath: string,
  crop: SourceTemplate['sourceCrop'],
): Promise<Buffer> {
  const image = sharp(sourcePath);
  if (!crop) return image.png().toBuffer();
  const metadata = await image.metadata();
  if (
    !metadata.width
    || !metadata.height
    || crop.x < 0
    || crop.y < 0
    || crop.width <= 0
    || crop.height <= 0
    || crop.x + crop.width > metadata.width
    || crop.y + crop.height > metadata.height
  ) {
    throw new Error(`模板源裁剪框越界：${sourcePath}`);
  }
  return image.extract({
    left: crop.x,
    top: crop.y,
    width: crop.width,
    height: crop.height,
  }).png().toBuffer();
}

async function renderAnimatedTemplate(source: Buffer, targetPath: string): Promise<void> {
  const base = await sharp(source)
    .resize(TEMPLATE_SIZE, TEMPLATE_SIZE, { fit: 'cover' })
    .png()
    .toBuffer();
  const frames = await Promise.all([0, 1, 2, 1].map(async (step) => {
    const inset = step * 8;
    const scaled = await sharp(base)
      .resize(TEMPLATE_SIZE - inset * 2, TEMPLATE_SIZE - inset * 2)
      .png()
      .toBuffer();
    return sharp({
      create: {
        width: TEMPLATE_SIZE,
        height: TEMPLATE_SIZE,
        channels: 4,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      },
    }).composite([{ input: scaled, left: inset, top: inset }]).png().toBuffer();
  }));
  await ensureParent(targetPath);
  await sharp({
    create: {
      width: TEMPLATE_SIZE,
      height: TEMPLATE_SIZE * frames.length,
      pageHeight: TEMPLATE_SIZE,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  }).composite(frames.map((input, index) => ({
    input,
    left: 0,
    top: index * TEMPLATE_SIZE,
  }))).gif({ loop: 0, delay: [140, 140, 140, 140] }).toFile(targetPath);
}

async function renderTemplate(
  template: SourceTemplate,
  sourceRoot: string,
  outputRoot: string,
  version: string,
): Promise<ExpressionAsset> {
  const sourcePath = join(sourceRoot, template.source);
  const source = await prepareTemplateSource(sourcePath, template.sourceCrop);
  const extension = template.type === 'gif' ? 'gif' : 'webp';
  const fileName = posix.join('templates', `${template.id}.${extension}`);
  const thumbnailFileName = posix.join('thumbnails', `${template.id}.webp`);
  const outputPath = join(outputRoot, fileName);
  const thumbnailPath = join(outputRoot, thumbnailFileName);
  await ensureParent(outputPath);
  await ensureParent(thumbnailPath);
  if (template.type === 'gif') {
    await renderAnimatedTemplate(source, outputPath);
  } else {
    await sharp(source)
      .resize(TEMPLATE_SIZE, TEMPLATE_SIZE, { fit: 'cover' })
      .webp({ quality: 88 })
      .toFile(outputPath);
  }
  await sharp(source)
    .resize(256, 256, { fit: 'cover' })
    .webp({ quality: 82 })
    .toFile(thumbnailPath);
  return {
    id: template.id,
    type: 'template',
    format: template.type === 'gif' ? 'gif' : 'webp',
    version,
    fileName,
    thumbnailFileName,
    sha256: await sha256(outputPath),
    width: TEMPLATE_SIZE,
    height: TEMPLATE_SIZE,
    keywords: template.keywords,
    emotions: template.emotions,
    textSafeArea: template.textSafeArea,
    layout: template.layout,
    heat: 0,
  };
}

async function renderEmojiBase(
  base: SourceEmojiBase,
  index: number,
  sourceRoot: string,
  outputRoot: string,
  version: string,
): Promise<EmojiBase> {
  const fileName = posix.join('emoji-base', `${base.id}.webp`);
  const outputPath = join(outputRoot, fileName);
  await ensureParent(outputPath);
  await sharp(join(sourceRoot, base.source))
    .resize(EMOJI_SIZE, EMOJI_SIZE, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .webp({ quality: 90 })
    .toFile(outputPath);
  return {
    id: base.id,
    name: base.name,
    emotions: base.emotions,
    fileName,
    sha256: await sha256(outputPath),
    version,
    width: EMOJI_SIZE,
    height: EMOJI_SIZE,
    sortOrder: index,
  };
}

async function renderEmojiCombination(
  first: EmojiBase,
  second: EmojiBase,
  outputRoot: string,
  version: string,
): Promise<EmojiCombination> {
  const key = emojiCombinationKey(first.id, second.id);
  const fileName = posix.join('emoji-combinations', `${key}.webp`);
  const outputPath = join(outputRoot, fileName);
  const firstImage = await sharp(join(outputRoot, first.fileName))
    .resize(224, 224)
    .toBuffer();
  const secondImage = await sharp(join(outputRoot, second.fileName))
    .resize(92, 92)
    .toBuffer();
  await ensureParent(outputPath);
  await sharp({
    create: {
      width: EMOJI_SIZE,
      height: EMOJI_SIZE,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  }).composite([
    { input: firstImage, left: 16, top: 8 },
    { input: secondImage, left: 156, top: 156 },
  ]).webp({ quality: 88 }).toFile(outputPath);
  return {
    key,
    firstId: first.id,
    secondId: second.id,
    fileName,
    sha256: await sha256(outputPath),
    version,
    width: EMOJI_SIZE,
    height: EMOJI_SIZE,
    heat: 0,
  };
}

async function copyAndroidSubset(
  catalog: GeneratedExpressionCatalog,
  manifest: ExpressionSourceManifest,
  outputRoot: string,
  androidAssetsRoot: string,
): Promise<void> {
  await rm(androidAssetsRoot, { recursive: true, force: true });
  await mkdir(androidAssetsRoot, { recursive: true });
  const builtInTemplateIds = new Set(manifest.builtInTemplateIds ?? []);
  const highFrequencyCombinations = new Set(manifest.highFrequencyCombinations ?? []);
  const relativeFiles = [
    ...catalog.emojiBases.map((item) => item.fileName),
    ...catalog.templates.map((item) => item.thumbnailFileName).filter((item): item is string => Boolean(item)),
    ...catalog.templates.filter((item) => builtInTemplateIds.has(item.id)).map((item) => item.fileName),
    ...catalog.emojiCombinations
      .filter((item) => highFrequencyCombinations.has(item.key))
      .map((item) => item.fileName),
  ];
  for (const relativePath of relativeFiles) {
    const target = join(androidAssetsRoot, relativePath);
    await ensureParent(target);
    await copyFile(join(outputRoot, relativePath), target);
  }
  await writeFile(
    join(androidAssetsRoot, 'catalog.json'),
    `${JSON.stringify(catalog, null, 2)}\n`,
  );
}

export async function generateExpressionAssets(
  options: GenerateExpressionAssetsOptions,
): Promise<GeneratedExpressionCatalog> {
  const manifest = JSON.parse(
    await readFile(options.manifestPath, 'utf8'),
  ) as ExpressionSourceManifest;
  validateManifest(manifest);
  await rm(options.outputRoot, { recursive: true, force: true });
  await mkdir(options.outputRoot, { recursive: true });

  const templates: ExpressionAsset[] = [];
  for (const template of manifest.templates) {
    templates.push(await renderTemplate(
      template,
      options.sourceRoot,
      options.outputRoot,
      manifest.version,
    ));
  }
  const emojiBases: EmojiBase[] = [];
  for (const [index, base] of manifest.emojiBases.entries()) {
    emojiBases.push(await renderEmojiBase(
      base,
      index,
      options.sourceRoot,
      options.outputRoot,
      manifest.version,
    ));
  }
  const emojiCombinations: EmojiCombination[] = [];
  for (const first of emojiBases) {
    for (const second of emojiBases) {
      emojiCombinations.push(await renderEmojiCombination(
        first,
        second,
        options.outputRoot,
        manifest.version,
      ));
    }
  }

  const catalog: GeneratedExpressionCatalog = {
    version: manifest.version,
    templates,
    emojiBases,
    emojiCombinations,
  };
  await writeFile(
    join(options.outputRoot, 'catalog.json'),
    `${JSON.stringify(catalog, null, 2)}\n`,
  );
  if (options.androidAssetsRoot) {
    await copyAndroidSubset(
      catalog,
      manifest,
      options.outputRoot,
      options.androidAssetsRoot,
    );
  }
  return catalog;
}
