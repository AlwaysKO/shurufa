import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, posix, relative, resolve, sep } from 'node:path';
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
  /** 已批准的无字动作原件；存在时禁止走静态缩放动画生成器。 */
  animation?: {
    sha256: string;
    sourceType: NonNullable<ExpressionAsset['sourceType']>;
    provenance: { manifest: string; itemId: string; approvalRecord: string };
  };
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

interface SourcePrebuiltPhrase {
  idPrefix?: string;
  text: string;
  aliases: string[];
  templateIds: string[];
}

interface SourcePrebuiltAsset {
  distribution?: 'bundled' | 'remote';
  id: string;
  source: string;
  sha256: string;
  embeddedText: string;
  keywords: string[];
  emotions: string[];
  style: string;
  sourceType: 'ai-original' | 'cc0' | 'public-domain' | 'licensed';
  provenance: {
    manifest?: string;
    itemId?: string;
    sourceUrl?: string;
    license?: string;
  };
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
  prebuiltPhrases?: SourcePrebuiltPhrase[];
  prebuiltAssets?: SourcePrebuiltAsset[];
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
  assertUniqueIds([
    ...manifest.templates,
    ...(manifest.prebuiltAssets ?? []),
    ...(manifest.prebuiltPhrases ?? []).flatMap((phrase, index) => phrase.templateIds.map((id) => ({
      id: `${phrase.idPrefix ?? `prebuilt-${String(index + 1).padStart(2, '0')}`}-${id}`,
    }))),
  ], '素材');
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
  const bundledPerWord = new Map<string, number>();
  for (const asset of manifest.prebuiltAssets ?? []) {
    if (asset.distribution === 'remote') continue;
    const word = asset.embeddedText?.trim();
    const count = (bundledPerWord.get(word) ?? 0) + 1;
    if (count > 4) throw new Error(`每词最多内置4张预制GIF：${word}`);
    bundledPerWord.set(word, count);
  }
  const templateIds = new Set(manifest.templates.map(({ id }) => id));
  for (const id of manifest.builtInTemplateIds ?? []) {
    if (!templateIds.has(id)) throw new Error(`内置清单引用未知模板：${id}`);
  }
  const phraseTexts = new Set<string>();
  for (const phrase of manifest.prebuiltPhrases ?? []) {
    if (phrase.idPrefix !== undefined && (typeof phrase.idPrefix !== 'string' || !ID_PATTERN.test(phrase.idPrefix) || phrase.idPrefix.includes('__'))) {
      throw new Error('预制短语 ID 前缀非法');
    }
    const text = phrase.text.trim();
    if (!text) throw new Error('预制短语不能为空');
    if (phraseTexts.has(text)) throw new Error(`预制短语重复：${text}`);
    phraseTexts.add(text);
    if (phrase.templateIds.length === 0) throw new Error(`预制短语未关联模板：${text}`);
    for (const id of phrase.templateIds) {
      if (!templateIds.has(id)) throw new Error(`预制短语引用未知模板：${text}/${id}`);
    }
  }
  const emojiIds = new Set(manifest.emojiBases.map(({ id }) => id));
  for (const key of manifest.highFrequencyCombinations ?? []) {
    const [firstId, secondId, extra] = key.split('__');
    if (extra !== undefined || !emojiIds.has(firstId) || !emojiIds.has(secondId)) {
      throw new Error(`内置清单引用未知组合：${key}`);
    }
  }
}

/** 无字动作模板在删除任何旧输出前完成来源、文字区域和全帧预检。 */
async function validateAnimatedTemplates(templates: SourceTemplate[], sourceRoot: string): Promise<Map<string, Buffer>> {
  const result = new Map<string, Buffer>();
  const native = templates.filter(template => template.animation !== undefined);
  if (native.length === 0) return result;
  const root = await realpath(sourceRoot);
  const withinRoot = (path: string) => {
    const rel = relative(root, path);
    return rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel);
  };
  const localPath = async (path: string) => {
    if (typeof path !== 'string' || !path || isAbsolute(path) || path.includes('\\') || !withinRoot(resolve(root, path))) {
      throw new Error('动作模板路径越界');
    }
    const actual = await realpath(resolve(root, path));
    if (!withinRoot(actual)) throw new Error('动作模板路径越界');
    return actual;
  };
  for (const template of native) {
    const animation = template.animation;
    if (!animation || template.type !== 'gif' || template.sourceCrop !== undefined
      || !['ai-original', 'cc0', 'public-domain', 'licensed'].includes(animation.sourceType)
      || !animation.provenance?.manifest?.trim() || !animation.provenance.itemId?.trim()) {
      throw new Error(`动作模板来源无效：${template.id}`);
    }
    const approval = JSON.parse(await readFile(await localPath(animation.provenance.approvalRecord), 'utf8'));
    if (approval.status !== 'approved' || !Array.isArray(approval.approvedIds) || !approval.approvedIds.includes(template.id)
      || (animation.sourceType === 'licensed' && (!Array.isArray(approval.licensedIds) || !approval.licensedIds.includes(template.id)))) {
      throw new Error(`动作模板缺少批准或授权记录：${template.id}`);
    }
    if (!Array.isArray(approval.approvedAssets) || !approval.approvedAssets.some(
      (asset: { id: string; sourceType: string; sha256: string }) => asset.id === template.id
        && asset.sourceType === animation.sourceType && asset.sha256 === animation.sha256,
    )) throw new Error(`动作模板版本与批准记录不一致：${template.id}`);
    const area = template.textSafeArea;
    const layout = template.layout;
    if (!area || ![area.x, area.y, area.width, area.height].every(Number.isInteger)
      || area.x < 0 || area.y < 0 || area.width <= 0 || area.height <= 0
      || area.x + area.width > 240 || area.y + area.height > 240
      || !layout || ![layout.minFontSize, layout.maxFontSize, layout.strokeWidth, layout.maxLines].every(Number.isInteger)
      || layout.minFontSize <= 0 || layout.maxFontSize < layout.minFontSize || layout.strokeWidth < 0
      || layout.maxLines < 1 || !['start', 'center', 'end'].includes(layout.alignment)
      || !/^#[a-f0-9]{6}$/i.test(layout.textColor) || !/^#[a-f0-9]{6}$/i.test(layout.strokeColor)) {
      throw new Error(`动作模板文字区域或排版无效：${template.id}`);
    }
    const bytes = await readFile(await localPath(template.source));
    if (!/^[a-f0-9]{64}$/.test(animation.sha256) || createHash('sha256').update(bytes).digest('hex') !== animation.sha256) {
      throw new Error(`动作模板 SHA-256 不符：${template.id}`);
    }
    const metadata = await sharp(bytes, { animated: true }).metadata();
    const duration = (metadata.delay ?? []).reduce((a, b) => a + b, 0);
    if (metadata.format !== 'gif' || metadata.width !== 240 || metadata.pageHeight !== 240
      || (metadata.pages ?? 1) < 10 || (metadata.pages ?? 1) > 20 || duration < 800 || duration > 4000
      || metadata.loop !== 0 || bytes.length >= 250 * 1024) {
      throw new Error(`动作 GIF 质量不合格：${template.id}`);
    }
    await sharp(bytes, { animated: true }).raw().toBuffer();
    result.set(template.id, bytes);
  }
  return result;
}

// Validate and retain the exact accepted bytes before deleting either output directory.
async function validatePrebuiltAssets(
  assets: SourcePrebuiltAsset[],
  sourceRoot: string,
): Promise<Map<string, Buffer>> {
  const sources = new Map<string, Buffer>();
  if (assets.length === 0) return sources;
  const root = await realpath(sourceRoot);
  const isOutside = (path: string) => {
    const rel = relative(root, path);
    return rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel);
  };
  for (const asset of assets) {
    if (asset.distribution !== undefined && !['bundled', 'remote'].includes(asset.distribution)) {
      throw new Error(`预制素材分发无效：${asset.id}`);
    }
    if (!asset.embeddedText?.trim()) throw new Error(`预制素材文字不能为空：${asset.id}`);
    if (!asset.style?.trim()) throw new Error(`预制素材风格不能为空：${asset.id}`);
    if (!Array.isArray(asset.keywords) || !asset.keywords.includes(asset.embeddedText)
      || !asset.keywords.every((keyword) => typeof keyword === 'string' && keyword.trim())
      || !Array.isArray(asset.emotions)
      || !asset.emotions.every((emotion) => typeof emotion === 'string' && emotion.trim())) {
      throw new Error(`预制素材关键词或情绪无效：${asset.id}`);
    }
    if (!['ai-original', 'cc0', 'public-domain', 'licensed'].includes(asset.sourceType)) {
      throw new Error(`预制素材来源无效：${asset.id}`);
    }
    if (asset.sourceType === 'ai-original') {
      if (!asset.provenance?.manifest?.trim() || !asset.provenance.itemId?.trim()) {
        throw new Error(`原创素材缺少来源记录：${asset.id}`);
      }
    } else if (!asset.provenance?.sourceUrl?.match(/^https?:\/\/[^\s]+$/)
      || !asset.provenance.license?.trim()) {
      throw new Error(`外部素材缺少来源或许可证：${asset.id}`);
    }
    if (typeof asset.source !== 'string' || !asset.source || isAbsolute(asset.source)
      || asset.source.includes('\\') || isOutside(resolve(root, asset.source))) {
      throw new Error(`预制素材路径越界：${asset.id}`);
    }
    const sourcePath = await realpath(resolve(root, asset.source));
    if (isOutside(sourcePath)) throw new Error(`预制素材路径越界：${asset.id}`);
    const bytes = await readFile(sourcePath);
    if (!/^[a-f0-9]{64}$/.test(asset.sha256)
      || createHash('sha256').update(bytes).digest('hex') !== asset.sha256) {
      throw new Error(`预制素材 SHA-256 不符：${asset.id}`);
    }
    const metadata = await sharp(bytes, { animated: true }).metadata();
    const frames = metadata.pages ?? 1;
    const duration = (metadata.delay ?? []).reduce((total, delay) => total + delay, 0);
    if (metadata.format !== 'gif' || metadata.width !== 240 || metadata.pageHeight !== 240
      || frames < 10 || frames > 20 || duration < 800 || duration > 4000
      || metadata.loop !== 0 || bytes.length >= 250 * 1024) {
      throw new Error(`预制 GIF 质量不合格：${asset.id}`);
    }
    // Decode all frames now so corrupt image data cannot destroy existing outputs.
    await sharp(bytes, { animated: true }).raw().toBuffer();
    sources.set(asset.id, bytes);
  }
  return sources;
}

async function copyPrebuiltAsset(
  asset: SourcePrebuiltAsset,
  bytes: Buffer,
  outputRoot: string,
  version: string,
): Promise<ExpressionAsset> {
  const fileName = posix.join('prebuilt', `${asset.id}.gif`);
  const thumbnailFileName = posix.join('thumbnails', `${asset.id}.webp`);
  await ensureParent(join(outputRoot, fileName));
  await ensureParent(join(outputRoot, thumbnailFileName));
  await writeFile(join(outputRoot, fileName), bytes);
  await sharp(bytes, { page: 0, pages: 1 }).webp({ lossless: true })
    .toFile(join(outputRoot, thumbnailFileName));
  return {
    id: asset.id, type: 'prebuilt', format: 'gif', version, fileName, thumbnailFileName,
    distribution: asset.distribution ?? 'bundled',
    sourceType: asset.sourceType,
    sha256: asset.sha256, width: 240, height: 240,
    keywords: asset.keywords, emotions: asset.emotions, embeddedText: asset.embeddedText,
    textSafeArea: null, layout: null, heat: 0,
  };
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
  animationBytes?: Buffer,
): Promise<ExpressionAsset> {
  if (animationBytes) {
    const fileName = posix.join('templates', `${template.id}.gif`);
    const thumbnailFileName = posix.join('thumbnails', `${template.id}.webp`);
    await ensureParent(join(outputRoot, fileName));
    await ensureParent(join(outputRoot, thumbnailFileName));
    await writeFile(join(outputRoot, fileName), animationBytes);
    await sharp(animationBytes, { page: 0, pages: 1 }).webp({ lossless: true }).toFile(join(outputRoot, thumbnailFileName));
    return {
      id: template.id, type: 'synthesis-template', format: 'gif', version, fileName, thumbnailFileName,
      sha256: template.animation!.sha256, sourceType: template.animation!.sourceType,
      width: 240, height: 240, keywords: template.keywords, emotions: template.emotions,
      embeddedText: null, textSafeArea: template.textSafeArea, layout: template.layout, heat: 0,
    };
  }
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
    type: 'synthesis-template',
    format: template.type === 'gif' ? 'gif' : 'webp',
    version,
    fileName,
    thumbnailFileName,
    sha256: await sha256(outputPath),
    width: TEMPLATE_SIZE,
    height: TEMPLATE_SIZE,
    keywords: template.keywords,
    emotions: template.emotions,
    embeddedText: null,
    textSafeArea: template.textSafeArea,
    layout: template.layout,
    heat: 0,
  };
}

function escapeXml(value: string): string {
  return value.replace(/[<>&"']/g, (character) => ({
    '<': '&lt;',
    '>': '&gt;',
    '&': '&amp;',
    '"': '&quot;',
    "'": '&apos;',
  })[character]!);
}

function textOverlay(
  text: string,
  safeArea: ExpressionTextSafeArea,
  layout: ExpressionTextLayout,
): Buffer {
  const characterCount = Math.max(1, Array.from(text).length);
  const fontSize = Math.max(
    layout.minFontSize,
    Math.min(layout.maxFontSize, Math.floor(safeArea.width / (characterCount * 1.1))),
  );
  const x = layout.alignment === 'start'
    ? safeArea.x
    : layout.alignment === 'end'
      ? safeArea.x + safeArea.width
      : safeArea.x + safeArea.width / 2;
  const anchor = layout.alignment === 'start'
    ? 'start'
    : layout.alignment === 'end'
      ? 'end'
      : 'middle';
  const y = safeArea.y + safeArea.height / 2 + fontSize * 0.35;
  return Buffer.from(`<svg width="${TEMPLATE_SIZE}" height="${TEMPLATE_SIZE}" xmlns="http://www.w3.org/2000/svg">
    <text x="${x}" y="${y}" text-anchor="${anchor}"
      font-family="Droid Sans Fallback, Source Han Serif SC, Noto Sans CJK SC, sans-serif"
      font-size="${fontSize}" font-weight="700"
      fill="${escapeXml(layout.textColor)}" stroke="${escapeXml(layout.strokeColor)}"
      stroke-width="${layout.strokeWidth}" paint-order="stroke fill"
      stroke-linejoin="round">${escapeXml(text)}</text>
  </svg>`);
}

async function renderPrebuilt(
  phrase: SourcePrebuiltPhrase,
  phraseIndex: number,
  template: SourceTemplate,
  templateAsset: ExpressionAsset,
  outputRoot: string,
  version: string,
): Promise<ExpressionAsset> {
  const id = `${phrase.idPrefix ?? `prebuilt-${String(phraseIndex + 1).padStart(2, '0')}`}-${template.id}`;
  const fileName = posix.join('prebuilt', `${id}.webp`);
  const thumbnailFileName = posix.join('thumbnails', `${id}.webp`);
  const outputPath = join(outputRoot, fileName);
  const thumbnailPath = join(outputRoot, thumbnailFileName);
  const base = await sharp(join(outputRoot, templateAsset.fileName), { pages: 1 })
    .resize(TEMPLATE_SIZE, TEMPLATE_SIZE, { fit: 'cover' })
    .toBuffer();
  await ensureParent(outputPath);
  await ensureParent(thumbnailPath);
  await sharp(base)
    .composite([{ input: textOverlay(phrase.text, template.textSafeArea, template.layout) }])
    .webp({ quality: 88 })
    .toFile(outputPath);
  await sharp(outputPath)
    .resize(256, 256, { fit: 'cover' })
    .webp({ quality: 82 })
    .toFile(thumbnailPath);
  return {
    id,
    type: 'prebuilt',
    format: 'webp',
    version,
    fileName,
    thumbnailFileName,
    sha256: await sha256(outputPath),
    width: TEMPLATE_SIZE,
    height: TEMPLATE_SIZE,
    keywords: [phrase.text, ...phrase.aliases],
    emotions: template.emotions,
    embeddedText: phrase.text,
    textSafeArea: null,
    layout: null,
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
    ...catalog.templates.filter((item) => item.type === 'prebuilt' && item.distribution !== 'remote').map((item) => item.fileName),
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
  const animationSources = await validateAnimatedTemplates(manifest.templates, options.sourceRoot);
  const prebuiltSources = await validatePrebuiltAssets(manifest.prebuiltAssets ?? [], options.sourceRoot);
  await rm(options.outputRoot, { recursive: true, force: true });
  await mkdir(options.outputRoot, { recursive: true });

  const templates: ExpressionAsset[] = [];
  for (const asset of manifest.prebuiltAssets ?? []) {
    templates.push(await copyPrebuiltAsset(
      asset, prebuiltSources.get(asset.id)!, options.outputRoot, manifest.version,
    ));
  }
  for (const template of manifest.templates) {
    templates.push(await renderTemplate(
      template,
      options.sourceRoot,
      options.outputRoot,
      manifest.version,
      animationSources.get(template.id),
    ));
  }
  const sourceTemplates = new Map(manifest.templates.map((template) => [template.id, template]));
  const templateAssets = new Map(templates.map((template) => [template.id, template]));
  for (const [phraseIndex, phrase] of (manifest.prebuiltPhrases ?? []).entries()) {
    for (const templateId of phrase.templateIds) {
      templates.push(await renderPrebuilt(
        phrase,
        phraseIndex,
        sourceTemplates.get(templateId)!,
        templateAssets.get(templateId)!,
        options.outputRoot,
        manifest.version,
      ));
    }
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
