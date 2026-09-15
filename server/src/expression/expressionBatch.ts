import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import sharp, { type OverlayOptions } from 'sharp';
import { PROTOTYPE_DIRECTIONS, PROTOTYPE_MOTION_PRESETS, PROTOTYPE_SOURCE_TYPES, PROTOTYPE_STYLES, PROTOTYPE_TEXT_PLACEMENTS, type ExpressionRenderItem } from './prototypeManifest.js';
import { auditPrototypeGif, type PrototypeGifAuditResult } from './prototypeAudit.js';
import { PROTOTYPE_FONT_PATH, renderPrototypeGif } from './prototypeRenderer.js';
import { publishDirectoryAtomically } from './prototypePublication.js';

export const DAILY_BATCH_KEYWORDS = ['你好', '早安', '晚安', '好的', '对不起'] as const;
const BATCH_KEYWORDS = {
  'daily-01': DAILY_BATCH_KEYWORDS,
  'daily-02': ['哈哈', '加油', '收到', '可以', '再见'],
  'daily-03': ['开心', '难过', '生气', '震惊', '抱抱'],
  'action-01': ['打闹', '追赶'],
  'semantic-01': ['懂了'],
} as const;
export type ExpressionBatchId = keyof typeof BATCH_KEYWORDS;
function batchId(value: unknown = 'daily-01'): ExpressionBatchId {
  return enumeration(value, ['daily-01', 'daily-02', 'daily-03', 'action-01', 'semantic-01'], 'batch');
}
export function resolveExpressionBatchPaths(root: string, value: unknown = 'daily-01') {
  const batch = batchId(value);
  return { batch, sourceRoot: join(root, 'assets/expression/batches', batch),
    outputRoot: join(root, 'artifacts/expression-batches', batch) };
}

export interface ExpressionPoseRect { left: number; top: number; width: number; height: number }
export interface ExpressionBatchItem extends ExpressionRenderItem {
  poseRects?: ExpressionPoseRect[];
  distribution: 'bundled' | 'remote';
  motionScript: string[];
  generation?: Record<string, unknown>;
}
export interface ExpressionBatchManifest { version: string; items: ExpressionBatchItem[] }
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function string(value: unknown, name: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${name} 必须是非空字符串`);
  return value;
}
function enumeration<T extends string>(value: unknown, allowed: readonly T[], name: string): T {
  const result = string(value, name);
  const match = allowed.find(candidate => candidate === result);
  if (match === undefined) throw new Error(`${name} 不在白名单内`);
  return match;
}
function integer(value: unknown, min: number, max: number, name: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) throw new Error(`${name} 必须在 ${min}–${max} 之间`);
  return value;
}
function validatePoseRects(value: unknown): ExpressionPoseRect[] {
  if (!Array.isArray(value) || value.length !== 4) throw new Error('poseRects 必须恰好四项');
  return value.map(rect => {
    if (!record(rect)) throw new Error('poseRects 每项必须是矩形对象');
    return {
      left: integer(rect.left, 0, Number.MAX_SAFE_INTEGER, 'poseRects.left'),
      top: integer(rect.top, 0, Number.MAX_SAFE_INTEGER, 'poseRects.top'),
      width: integer(rect.width, 1, Number.MAX_SAFE_INTEGER, 'poseRects.width'),
      height: integer(rect.height, 1, Number.MAX_SAFE_INTEGER, 'poseRects.height'),
    };
  });
}
export function validateExpressionBatchManifest(value: unknown, batch: unknown = 'daily-01'): ExpressionBatchManifest {
  const selectedBatch = batchId(batch);
  const keywords = BATCH_KEYWORDS[selectedBatch];
  const remoteCount = selectedBatch === 'action-01' || selectedBatch === 'semantic-01' ? 0 : 4;
  const expectedTotal = keywords.length * (4 + remoteCount);
  if (!record(value) || !Array.isArray(value.items) || value.items.length !== expectedTotal) throw new Error(`批次必须恰好${expectedTotal}项`);
  const version = string(value.version, 'version');
  const paths = new Set<string>();
  const ids = new Set<string>();
  function uniquePath(path: string) {
    if (paths.has(path)) throw new Error(`路径必须唯一：${path}`);
    paths.add(path);
    return path;
  }
  const items = value.items.map((raw): ExpressionBatchItem => {
    if (!record(raw)) throw new Error('每项必须是对象');
    const id = string(raw.id, 'id');
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(id) || ids.has(id)) throw new Error(`ID非法或重复：${id}`);
    ids.add(id);
    const keyword = enumeration(raw.keyword, keywords, 'keyword');
    const text = string(raw.text, 'text');
    if (text !== keyword) throw new Error(`${id}: text 必须等于 keyword`);
    const sourceType = enumeration(raw.sourceType, PROTOTYPE_SOURCE_TYPES, 'sourceType');
    const sourceUrl = raw.sourceUrl === undefined ? undefined : string(raw.sourceUrl, 'sourceUrl');
    const license = raw.license === undefined ? undefined : string(raw.license, 'license');
    if (sourceType !== 'ai-original' && (!sourceUrl || !license)) throw new Error(`${id}: 外部素材必须记录来源和许可证`);
    const prompt = string(raw.prompt, 'prompt');
    if (!['原创', '无文字', '无水印', '无品牌', '无现有角色'].every(term => prompt.includes(term))) throw new Error(`${id}: prompt 缺少安全约束`);
    const masterFile = string(raw.masterFile, 'masterFile');
    if (masterFile !== `masters/${id}.png`) throw new Error(`${id}: masterFile 必须匹配 masters/<id>.png`);
    uniquePath(masterFile);
    if (!Array.isArray(raw.poseFiles) || raw.poseFiles.length !== 4) throw new Error(`${id}: poseFiles 必须四项`);
    const poseFiles = raw.poseFiles.map((path, i) => {
      if (path !== `poses/${id}/pose-0${i + 1}.png`) throw new Error(`${id}: poseFiles 路径不匹配`);
      return uniquePath(path);
    });
    if (!Array.isArray(raw.motionScript) || raw.motionScript.length !== 4) throw new Error(`${id}: motionScript 必须四项`);
    const motionScript = raw.motionScript.map(line => string(line, 'motionScript'));
    const direction = enumeration(raw.direction, PROTOTYPE_DIRECTIONS, 'direction');
    const textPlacement = enumeration(raw.textPlacement, PROTOTYPE_TEXT_PLACEMENTS, 'textPlacement');
    if ((direction === 'kinetic-type') !== (textPlacement === 'center')) throw new Error(`${id}: textPlacement 与 direction 不匹配`);
    if (raw.generation !== undefined && !record(raw.generation)) throw new Error(`${id}: generation 必须是对象`);
    return { id, keyword, text, sourceType, sourceUrl, license, prompt, masterFile, poseFiles, motionScript,
      ...(raw.poseRects === undefined ? {} : { poseRects: validatePoseRects(raw.poseRects) }),
      style: enumeration(raw.style, PROTOTYPE_STYLES, 'style'), direction, textPlacement,
      motionPreset: enumeration(raw.motionPreset, PROTOTYPE_MOTION_PRESETS, 'motionPreset'),
      frameCount: integer(raw.frameCount, 10, 20, 'frameCount'), durationMs: integer(raw.durationMs, 800, 2000, 'durationMs'),
      distribution: enumeration(raw.distribution, ['bundled', 'remote'], 'distribution'),
      ...(raw.generation === undefined ? {} : { generation: raw.generation }),
    };
  });
  for (const keyword of keywords) {
    for (const distribution of ['bundled', 'remote']) {
      if (items.filter(item => item.keyword === keyword && item.distribution === distribution).length !== (distribution === 'bundled' ? 4 : remoteCount)) throw new Error(`${keyword}: 必须4 bundled + ${remoteCount} remote`);
    }
  }
  return { version, items };
}

/** 显式矩形或缺省中线无损裁切；奇数尺寸余下1px归右/下，保留全部像素与alpha。 */
export async function splitBatchMaster(source: Buffer, explicitRects?: ExpressionPoseRect[]): Promise<Buffer[]> {
  const meta = await sharp(source).metadata();
  if (meta.format !== 'png' || !meta.hasAlpha || !meta.width || !meta.height || meta.width < 2 || meta.height < 2) throw new Error('master 必须为带alpha、宽高至少2px的PNG 2×2姿势表');
  const sourceWidth = meta.width, sourceHeight = meta.height;
  const width = Math.floor(sourceWidth / 2), height = Math.floor(sourceHeight / 2);
  const rectangles = explicitRects === undefined ? [0, 1, 2, 3].map(index => ({
    left: index % 2 * width,
    top: Math.floor(index / 2) * height,
    width: index % 2 === 0 ? width : sourceWidth - width,
    height: index < 2 ? height : sourceHeight - height,
  })) : validatePoseRects(explicitRects);
  for (const [i, rect] of rectangles.entries()) {
    if (rect.left + rect.width > sourceWidth || rect.top + rect.height > sourceHeight) throw new Error('poseRects 超出原PNG边界');
    for (const other of rectangles.slice(i + 1)) {
      if (rect.left < other.left + other.width && other.left < rect.left + rect.width
        && rect.top < other.top + other.height && other.top < rect.top + rect.height) throw new Error('poseRects 不得相互重叠');
    }
  }
  // 全部在界内、互不重叠且面积相等，保证恰好覆盖原图，不丢弃任何像素。
  if (rectangles.reduce((area, rect) => area + rect.width * rect.height, 0) !== sourceWidth * sourceHeight) throw new Error('poseRects 必须完整覆盖原PNG');
  const poses = await Promise.all(rectangles.map(rect => sharp(source).extract(rect).png().toBuffer()));
  const digests = new Set<string>();
  for (const pose of poses) {
    const pixels = await sharp(pose).ensureAlpha().raw().toBuffer();
    let visible = false, transparent = false;
    for (let offset = 3; offset < pixels.length; offset += 4) {
      if (pixels[offset] >= 8) visible = true;
      if (pixels[offset] < 255) transparent = true;
    }
    if (!visible || !transparent) throw new Error('每个姿势必须具有可见前景和透明背景');
    digests.add(createHash('sha256').update(pixels).digest('hex'));
  }
  if (digests.size !== 4) throw new Error('必须提供四个不同的关键姿势');
  return poses;
}
function escape(value: string): string {
  return value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]!);
}
interface Rendered { item: ExpressionBatchItem; gif: Buffer; thumbnail: Buffer; audit: PrototypeGifAuditResult }
async function contactSheet(rendered: Rendered[], path: string): Promise<void> {
  const layers: OverlayOptions[] = [];
  for (const [i, { item, thumbnail }] of rendered.entries()) {
    const left = i % 4 * 280 + 20, top = Math.floor(i / 4) * 300 + 10;
    const label = await sharp({ text: { text: escape(`${item.keyword} · ${item.distribution === 'bundled' ? '内置' : '联网'}\n${item.id}`), font: 'Droid Sans Fallback 13', fontfile: PROTOTYPE_FONT_PATH, width: 240, height: 48, align: 'centre', rgba: true } }).png().toBuffer();
    layers.push({ input: thumbnail, left, top }, { input: label, left, top: top + 240 });
  }
  await sharp({ create: { width: 1120, height: Math.ceil(rendered.length / 4) * 300, channels: 4, background: '#eeeeee' } }).composite(layers).webp({ quality: 92 }).toFile(path);
}
export async function renderExpressionBatch(options: { manifest: unknown; sourceRoot: string; outputRoot: string; ids?: string[]; batch?: ExpressionBatchId }) {
  const manifest = validateExpressionBatchManifest(options.manifest, options.batch);
  if (options.ids && (!options.ids.length || new Set(options.ids).size !== options.ids.length)) throw new Error('ids 不能为空或重复');
  for (const id of options.ids ?? []) if (!manifest.items.some(item => item.id === id)) throw new Error(`未知ID：${id}`);
  const selected = manifest.items.filter(item => !options.ids || options.ids.includes(item.id));
  const complete = selected.length === manifest.items.length;
  // 部分调试独立发布，绝不覆盖完整批次报告。
  const outputRoot = options.ids ? join(options.outputRoot, 'partial', createHash('sha256').update([...options.ids].sort().join(',')).digest('hex').slice(0, 16)) : options.outputRoot;
  const poseBuffers = new Map<string, Buffer[]>();
  for (const item of selected) {
    try { poseBuffers.set(item.id, await splitBatchMaster(await readFile(join(options.sourceRoot, item.masterFile)), item.poseRects)); }
    catch (error) { throw new Error(`${item.id}: master 不可用`, { cause: error }); }
  }
  const rendered: Rendered[] = [];
  await publishDirectoryAtomically(outputRoot, async temp => {
    await mkdir(join(temp, 'gifs')); await mkdir(join(temp, 'thumbnails'));
    for (const item of selected) {
      const poses = poseBuffers.get(item.id)!;
      const gif = await renderPrototypeGif({ masters: poses, item });
      const audit = await auditPrototypeGif(gif, item);
      const thumbnail = await sharp(gif, { page: 0 }).webp({ lossless: true }).toBuffer();
      rendered.push({ item, gif, thumbnail, audit });
      await writeFile(join(temp, 'gifs', `${item.id}.gif`), gif);
      await writeFile(join(temp, 'thumbnails', `${item.id}.webp`), thumbnail);
    }
    const report = makeReport();
    await writeFile(join(temp, 'report.json'), JSON.stringify(report, null, 2) + '\n');
    await contactSheet(rendered, join(temp, 'contact-sheet.webp'));
    for (const [index, keyword] of BATCH_KEYWORDS[batchId(options.batch)].entries()) {
      const group = rendered.filter(({ item }) => item.keyword === keyword);
      if (group.length) await contactSheet(group, join(temp, `contact-sheet-${index + 1}.webp`));
    }
    const cards = rendered.map(({ item, audit }) => `<article><img src="gifs/${item.id}.gif" width="240" height="240"><h2>${escape(item.keyword)} · ${item.distribution === 'bundled' ? '内置' : '联网'}</h2><code>${item.id}</code><p>${audit.metadata.pages}帧 / ${audit.metadata.durationMs}ms / ${audit.metadata.bytes} bytes</p></article>`).join('\n');
    await writeFile(join(temp, 'preview.html'), `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>日常图库评审</title><style>body{font-family:system-ui;background:#eee}main{display:flex;flex-wrap:wrap;gap:20px}article{padding:16px;background:white;max-width:280px}code{overflow-wrap:anywhere}</style><h1>${selected.length}/${manifest.items.length} 项 · 人工视觉验收待确认${complete ? '' : ' · 部分调试'}</h1><main>${cards}</main></html>`);
    if (report.fail) throw new Error(`批次审计失败：${report.fail}/${report.total}；${rendered.flatMap(r => r.audit.issues.map(issue => `${r.item.id}:${issue.message}`)).join('; ')}`);
    // 审计成功后保存姿势；全部保存成功才允许发布新报告与GIF。
    // 素材区写入失败时，原子发布器丢弃临时输出，保留上一版发布目录。
    for (const item of selected) for (const [i, pose] of poseBuffers.get(item.id)!.entries()) {
      const path = join(options.sourceRoot, item.poseFiles[i]);
      await mkdir(dirname(path), { recursive: true }); await writeFile(path, pose);
    }
  });
  function makeReport() {
    return { version: manifest.version, expectedTotal: manifest.items.length, total: rendered.length,
      pass: rendered.filter(r => !r.audit.issues.length).length, fail: rendered.filter(r => r.audit.issues.length).length,
      complete, humanReview: 'pending' as const,
      items: rendered.map(({ item, audit }) => ({ ...audit, keyword: item.keyword, distribution: item.distribution, humanReview: 'pending' as const })),
    };
  }
  return { outputRoot, report: makeReport() };
}
