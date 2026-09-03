import { access } from 'node:fs/promises';
import { constants as fsConstants } from 'node:fs';
import { fileURLToPath } from 'node:url';

import sharp, { type OverlayOptions } from 'sharp';

import type {
  PrototypeManifestItem,
  PrototypeMotionPreset,
  PrototypeTextPlacement,
} from './prototypeManifest.js';

const OUTPUT_SIZE = 240;
const BOTTOM_POSE_WIDTH = 184;
const BOTTOM_POSE_HEIGHT = 142;
const CENTER_POSE_SIZE = 216;
const MAX_TRANSFORMED_POSE_SIZE = 228;
const TEXT_CENTER_Y = 183;
const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 } as const;

export const PROTOTYPE_FONT_PATH = fileURLToPath(new URL(
  '../../../assets/expression/fonts/DroidSansFallbackFull.ttf',
  import.meta.url,
));

export interface PrototypeFramePlan {
  delayMs: number;
  poseIndex: number;
  translateX: number;
  translateY: number;
  rotationDeg: number;
  scaleX: number;
  scaleY: number;
  textScale: number;
  textRotationDeg: number;
}

export interface TextOverlayOptions {
  kinetic?: boolean;
  placement?: PrototypeTextPlacement;
  progress?: number;
}

export type RenderPrototypeGifOptions = {
  item: PrototypeManifestItem;
  masters: Buffer[];
  masterPaths?: never;
} | {
  item: PrototypeManifestItem;
  masters?: never;
  masterPaths: string[];
};

/** 在开始渲染前确认受控字体可读，避免静默退回到机器上的其他字体。 */
export async function assertPrototypeFontAvailable(
  fontPath = PROTOTYPE_FONT_PATH,
  itemId = '未知样板',
): Promise<void> {
  try {
    await access(fontPath, fsConstants.R_OK);
  } catch (error) {
    throw new Error(`固定字体缺失（样板 ${itemId}）：${fontPath}`, { cause: error });
  }
}

function escapeXml(value: string): string {
  return value
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/** 构建逐帧文字层；文字永远由确定性 SVG 排版而不是由主视觉提供。 */
export function buildTextOverlaySvg(
  text: string,
  frame: PrototypeFramePlan,
  options: TextOverlayOptions = {},
): Buffer {
  const progress = Math.min(1, Math.max(0, options.progress ?? 0));
  const kineticEnvelope = options.kinetic ? Math.sin(Math.PI * progress) : 0;
  const scale = frame.textScale * (1 + 0.13 * kineticEnvelope);
  const rotation = frame.textRotationDeg
    + (options.kinetic ? 4 * kineticEnvelope * Math.sin(4 * Math.PI * progress) : 0);
  const textCenterY = options.placement === 'center' ? OUTPUT_SIZE / 2 : TEXT_CENTER_Y;
  const characterCount = Math.max(1, Array.from(text).length);
  const fontSize = characterCount <= 2 ? 54
    : characterCount <= 4 ? 44
      : characterCount <= 6 ? 35
        : 28;
  const safeText = escapeXml(text);

  return Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="240" height="240" viewBox="0 0 240 240">
      <defs>
        <filter id="textShadow" x="-30%" y="-40%" width="160%" height="190%">
          <feDropShadow dx="0" dy="4" stdDeviation="2.5" flood-color="#000000" flood-opacity="0.68"/>
        </filter>
      </defs>
      <g transform="translate(120 ${textCenterY}) rotate(${rotation.toFixed(3)}) scale(${scale.toFixed(4)}) translate(-120 -${textCenterY})"
         filter="url(#textShadow)">
        <text x="120" y="${textCenterY + 15}" text-anchor="middle"
          font-family="Droid Sans Fallback, Source Han Serif SC, sans-serif"
          font-size="${fontSize}" font-weight="900"
          fill="#FFF7D6" stroke="#211711" stroke-width="8"
          stroke-linejoin="round" stroke-linecap="round"
          paint-order="stroke fill">${safeText}</text>
        <text x="120" y="${textCenterY + 12}" text-anchor="middle"
          font-family="Droid Sans Fallback, Source Han Serif SC, sans-serif"
          font-size="${fontSize}" font-weight="900"
          fill="#FFFFFF" stroke="#FF4D4F" stroke-width="3"
          stroke-linejoin="round" stroke-linecap="round"
          paint-order="stroke fill">${safeText}</text>
      </g>
    </svg>
  `);
}

/** 使用仓库内固定字体栅格化文字，再施加描边、阴影和逐帧动效。 */
export async function rasterizeTextOverlay(
  text: string,
  frame: PrototypeFramePlan,
  options: TextOverlayOptions = {},
): Promise<Buffer> {
  await assertPrototypeFontAvailable();
  const characterCount = Math.max(1, Array.from(text).length);
  const fontSize = characterCount <= 2 ? 54
    : characterCount <= 4 ? 44
      : characterCount <= 6 ? 35
        : 28;
  const progress = Math.min(1, Math.max(0, options.progress ?? 0));
  const kineticEnvelope = options.kinetic ? Math.sin(Math.PI * progress) : 0;
  const scale = frame.textScale * (1 + 0.13 * kineticEnvelope);
  const rotation = frame.textRotationDeg
    + (options.kinetic ? 4 * kineticEnvelope * Math.sin(4 * Math.PI * progress) : 0);
  const textCenterY = options.placement === 'center' ? OUTPUT_SIZE / 2 : TEXT_CENTER_Y;
  const renderedGlyph = await sharp({
    text: {
      text: escapeXml(text),
      font: `Droid Sans Fallback ${fontSize}`,
      fontfile: PROTOTYPE_FONT_PATH,
      rgba: true,
    },
  }).png().toBuffer({ resolveWithObject: true });
  const glyphAlpha = await sharp(renderedGlyph.data).extractChannel('alpha').png().toBuffer();
  const glyph = await sharp({
    create: {
      width: renderedGlyph.info.width,
      height: renderedGlyph.info.height,
      channels: 3,
      background: '#ffffff',
    },
  }).joinChannel(glyphAlpha).png().toBuffer();
  const x = OUTPUT_SIZE / 2 - renderedGlyph.info.width / 2;
  const y = textCenterY - renderedGlyph.info.height / 2;
  const glyphUrl = `data:image/png;base64,${glyph.toString('base64')}`;
  const svg = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
      width="240" height="240" viewBox="0 0 240 240">
      <defs>
        <filter id="outlinedText" x="-45%" y="-70%" width="190%" height="240%"
          color-interpolation-filters="sRGB">
          <feGaussianBlur in="SourceAlpha" stdDeviation="2.5" result="shadowBlur"/>
          <feOffset in="shadowBlur" dx="0" dy="4" result="shadowOffset"/>
          <feFlood flood-color="#000000" flood-opacity="0.68" result="shadowColour"/>
          <feComposite in="shadowColour" in2="shadowOffset" operator="in" result="shadow"/>
          <feMorphology in="SourceAlpha" operator="dilate" radius="7" result="outerMask"/>
          <feFlood flood-color="#211711" result="outerColour"/>
          <feComposite in="outerColour" in2="outerMask" operator="in" result="outer"/>
          <feMorphology in="SourceAlpha" operator="dilate" radius="3" result="innerMask"/>
          <feFlood flood-color="#FF4D4F" result="innerColour"/>
          <feComposite in="innerColour" in2="innerMask" operator="in" result="inner"/>
          <feMerge>
            <feMergeNode in="shadow"/>
            <feMergeNode in="outer"/>
            <feMergeNode in="inner"/>
            <feMergeNode in="SourceGraphic"/>
          </feMerge>
        </filter>
      </defs>
      <g transform="translate(120 ${textCenterY}) rotate(${rotation.toFixed(3)}) scale(${scale.toFixed(4)}) translate(-120 -${textCenterY})">
        <image x="${x.toFixed(2)}" y="${y.toFixed(2)}" width="${renderedGlyph.info.width}" height="${renderedGlyph.info.height}"
          href="${glyphUrl}" xlink:href="${glyphUrl}" filter="url(#outlinedText)"/>
      </g>
    </svg>
  `);
  return sharp(svg).png().toBuffer();
}

function buildEffectsSvg(
  motionPreset: PrototypeMotionPreset,
  frameIndex: number,
  frameCount: number,
): Buffer | undefined {
  if (motionPreset !== 'laugh' && motionPreset !== 'impact') return undefined;

  const progress = frameIndex / (frameCount - 1);
  const envelope = Math.sin(Math.PI * progress);
  if (motionPreset === 'impact') {
    const lines = [-155, -125, -55, -25, 25, 55, 125, 155].map((angle) => {
      const radians = angle * Math.PI / 180;
      const inner = 66 + 5 * Math.sin(progress * Math.PI * 2 + radians);
      const outer = inner + 17 + 8 * envelope;
      const x1 = 120 + Math.cos(radians) * inner;
      const y1 = 91 + Math.sin(radians) * inner * 0.75;
      const x2 = 120 + Math.cos(radians) * outer;
      const y2 = 91 + Math.sin(radians) * outer * 0.75;
      return `<line x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}"/>`;
    }).join('');
    return Buffer.from(`
      <svg xmlns="http://www.w3.org/2000/svg" width="240" height="240">
        <g opacity="${(0.9 * envelope).toFixed(3)}" stroke="#FFB800" stroke-width="6"
           stroke-linecap="round">${lines}</g>
      </svg>
    `);
  }

  const confetti = [
    [28, 60, '#FF4D4F'], [51, 30, '#FFD43B'], [190, 37, '#43D6C5'],
    [211, 72, '#8B5CF6'], [34, 112, '#43D6C5'], [204, 119, '#FF4D4F'],
  ].map(([x, y, colour], index) => {
    const sway = Math.sin(progress * Math.PI * 4 + index) * 5;
    const fall = envelope * (7 + index % 3 * 3);
    return `<rect x="${(Number(x) + sway).toFixed(1)}" y="${(Number(y) + fall).toFixed(1)}"
      width="7" height="12" rx="3" fill="${colour}"
      transform="rotate(${(progress * 180 + index * 31).toFixed(1)} ${x} ${y})"/>`;
  }).join('');
  return Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="240" height="240">
      <g opacity="${(0.92 * envelope).toFixed(3)}">${confetti}</g>
    </svg>
  `);
}

/** 校验并规范化单个透明姿势图；bottom 去透明边，center 保留画布内构图。 */
export async function preparePrototypePose(
  source: Buffer | string,
  item: PrototypeManifestItem,
  poseIndex: number,
): Promise<Buffer> {
  const poseNumber = poseIndex + 1;
  let metadata;
  try {
    metadata = await sharp(source).metadata();
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`无法读取姿势（样板 ${item.id}，姿势 ${poseNumber}）：${detail}`, { cause: error });
  }

  if (metadata.format !== 'png') {
    throw new Error(`姿势必须是 PNG（样板 ${item.id}，姿势 ${poseNumber}，实际为 ${metadata.format ?? '未知格式'}）`);
  }
  if (!metadata.width || !metadata.height) {
    throw new Error(`姿势缺少有效尺寸（样板 ${item.id}，姿势 ${poseNumber}）`);
  }
  if (!metadata.hasAlpha) {
    throw new Error(`姿势背景完全不透明（样板 ${item.id}，姿势 ${poseNumber}）`);
  }

  try {
    const oriented = await sharp(source)
      .rotate()
      .ensureAlpha()
      .png()
      .toBuffer();
    const { data, info } = await sharp(oriented).raw().toBuffer({ resolveWithObject: true });
    let minX = info.width;
    let minY = info.height;
    let maxX = -1;
    let maxY = -1;
    let nonTransparentPixels = 0;
    let allOpaque = true;
    for (let y = 0; y < info.height; y += 1) {
      for (let x = 0; x < info.width; x += 1) {
        const alpha = data[(y * info.width + x) * info.channels + 3];
        if (alpha !== 255) allOpaque = false;
        if (alpha === 0) continue;
        nonTransparentPixels += 1;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
    if (allOpaque) {
      throw new Error(`姿势背景完全不透明（样板 ${item.id}，姿势 ${poseNumber}）`);
    }
    if (nonTransparentPixels === 0) {
      throw new Error(`姿势为全透明空图（样板 ${item.id}，姿势 ${poseNumber}）`);
    }
    const alphaRatio = nonTransparentPixels / (info.width * info.height);
    if (alphaRatio < 0.005 || alphaRatio > 0.9) {
      throw new Error(`姿势 alpha 占比异常（样板 ${item.id}，姿势 ${poseNumber}，占比 ${alphaRatio.toFixed(4)}）`);
    }

    if (item.textPlacement === 'center') {
      return await sharp(oriented)
        .resize(CENTER_POSE_SIZE, CENTER_POSE_SIZE, {
          fit: 'contain',
          background: TRANSPARENT,
        })
        .png()
        .toBuffer();
    }
    return await sharp(oriented)
      .extract({
        left: minX,
        top: minY,
        width: maxX - minX + 1,
        height: maxY - minY + 1,
      })
      .resize(BOTTOM_POSE_WIDTH, BOTTOM_POSE_HEIGHT, {
        fit: 'contain',
        background: TRANSPARENT,
      })
      .png()
      .toBuffer();
  } catch (error) {
    if (error instanceof Error && error.message.includes(`样板 ${item.id}`)) throw error;
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`无法处理姿势（样板 ${item.id}，姿势 ${poseNumber}）：${detail}`, { cause: error });
  }
}

async function renderFrame(
  preparedPoses: Buffer[],
  item: PrototypeManifestItem,
  plan: PrototypeFramePlan,
  frameIndex: number,
): Promise<Buffer> {
  const pose = preparedPoses[plan.poseIndex];
  const poseMetadata = await sharp(pose).metadata();
  const width = Math.max(1, Math.round(poseMetadata.width! * plan.scaleX));
  const height = Math.max(1, Math.round(poseMetadata.height! * plan.scaleY));
  const rotated = await sharp(pose)
    .resize(width, height, { fit: 'fill' })
    .rotate(plan.rotationDeg, { background: TRANSPARENT })
    .png()
    .toBuffer();
  const transformed = await sharp(rotated)
    .resize(MAX_TRANSFORMED_POSE_SIZE, MAX_TRANSFORMED_POSE_SIZE, {
      fit: 'inside',
      withoutEnlargement: true,
    })
    .png()
    .toBuffer({ resolveWithObject: true });
  const left = Math.round(OUTPUT_SIZE / 2 + plan.translateX - transformed.info.width / 2);
  const poseCenterY = item.textPlacement === 'center' ? OUTPUT_SIZE / 2 : 72;
  const top = Math.round(poseCenterY + plan.translateY - transformed.info.height / 2);
  const progress = frameIndex / (item.frameCount - 1);
  const effects = buildEffectsSvg(item.motionPreset, frameIndex, item.frameCount);
  const text = await rasterizeTextOverlay(item.text, plan, {
    kinetic: item.direction === 'kinetic-type',
    placement: item.textPlacement,
    progress,
  });
  const layers: OverlayOptions[] = [
    { input: transformed.data, left, top },
    ...(effects === undefined ? [] : [{ input: effects, left: 0, top: 0 }]),
    { input: text, left: 0, top: 0 },
  ];

  return sharp({
    create: {
      width: OUTPUT_SIZE,
      height: OUTPUT_SIZE,
      channels: 4,
      background: TRANSPARENT,
    },
  }).composite(layers).png().toBuffer();
}

/** 将无文字 PNG 主视觉渲染为 240×240、无限循环的确定性动态 GIF。 */
export async function renderPrototypeGif(options: RenderPrototypeGifOptions): Promise<Buffer> {
  const { item } = options;
  if (!Number.isInteger(item.frameCount) || item.frameCount < 10 || item.frameCount > 20) {
    throw new Error(`frameCount 必须在 10–20 之间（样板 ${item.id}）`);
  }
  if (!Number.isInteger(item.durationMs) || item.durationMs < 800 || item.durationMs > 2_000) {
    throw new Error(`durationMs 必须在 800–2000 之间（样板 ${item.id}）`);
  }
  await assertPrototypeFontAvailable(PROTOTYPE_FONT_PATH, item.id);

  if (!Array.isArray(item.poseFiles) || item.poseFiles.length !== 4) {
    throw new Error(`poseFiles 必须恰好 4 个（样板 ${item.id}）`);
  }
  const hasBuffers = Array.isArray(options.masters);
  const hasPaths = Array.isArray(options.masterPaths);
  if (hasBuffers === hasPaths) {
    throw new Error(`masters 与 masterPaths 必须二选一（样板 ${item.id}）`);
  }
  const sources: Array<Buffer | string> = hasBuffers ? options.masters! : options.masterPaths!;
  const sourceLabel = hasBuffers ? 'masters' : 'masterPaths';
  if (sources.length !== 4) {
    throw new Error(`${sourceLabel} 必须恰好 4 个并与 poseFiles 对齐（样板 ${item.id}）`);
  }
  if (hasBuffers && !sources.every((source) => Buffer.isBuffer(source))) {
    throw new Error(`masters 必须全部为 Buffer（样板 ${item.id}）`);
  }
  if (hasPaths && !sources.every((source) => typeof source === 'string' && source.trim() !== '')) {
    throw new Error(`masterPaths 必须全部为非空路径（样板 ${item.id}）`);
  }
  const preparedPoses = await Promise.all(sources.map((source, poseIndex) => (
    preparePrototypePose(source, item, poseIndex)
  )));
  const plan = buildFramePlan(
    item.motionPreset,
    item.frameCount,
    item.durationMs,
  );
  const frames = await Promise.all(plan.map(async (frame, index) => {
    try {
      return await renderFrame(preparedPoses, item, frame, index);
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      throw new Error(`渲染样板 ${item.id} 第 ${index + 1} 帧失败：${detail}`, { cause: error });
    }
  }));
  try {
    const frameStrip = sharp({
      create: {
        width: OUTPUT_SIZE,
        height: OUTPUT_SIZE * frames.length,
        pageHeight: OUTPUT_SIZE,
        channels: 4,
        background: TRANSPARENT,
      },
    });
    return await frameStrip
      .composite(frames.map((input, index) => ({
        input,
        left: 0,
        top: index * OUTPUT_SIZE,
      })))
      .gif({
        loop: 0,
        delay: plan.map(({ delayMs }) => delayMs),
        colours: 192,
        dither: 0.7,
        effort: 6,
        keepDuplicateFrames: true,
      })
      .toBuffer();
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`编码样板 ${item.id} GIF 失败：${detail}`, { cause: error });
  }
}

const POSE_CYCLE = [0, 1, 2, 3, 2, 1, 0] as const;

/** 将任意合法帧数映射到完整、首尾闭合的四姿势往返序列。 */
export function buildPoseSequence(frameCount: number): number[] {
  if (!Number.isInteger(frameCount) || frameCount < POSE_CYCLE.length) {
    throw new Error(`frameCount 必须是至少为 ${POSE_CYCLE.length} 的整数`);
  }
  return Array.from({ length: frameCount }, (_, index) => {
    const cycleIndex = Math.round(index * (POSE_CYCLE.length - 1) / (frameCount - 1));
    return POSE_CYCLE[cycleIndex];
  });
}

export function buildFramePlan(
  motionPreset: PrototypeMotionPreset,
  frameCount: number,
  durationMs: number,
): PrototypeFramePlan[] {
  if (!Number.isInteger(frameCount) || frameCount < 2) {
    throw new Error('frameCount 必须是至少为 2 的整数');
  }
  if (!Number.isFinite(durationMs) || durationMs < frameCount * 10) {
    throw new Error('durationMs 必须保证每帧至少 10ms');
  }

  const durationUnits = Math.round(durationMs / 10);
  const baseUnits = Math.floor(durationUnits / frameCount);
  const remainder = durationUnits % frameCount;
  const poseSequence = buildPoseSequence(frameCount);
  const round = (value: number) => Math.abs(value) < 0.00005 ? 0 : Number(value.toFixed(4));

  return Array.from({ length: frameCount }, (_, index) => {
    const progress = index / (frameCount - 1);
    const envelope = Math.sin(Math.PI * progress);
    let translateX = 0;
    let translateY = 0;
    let rotationDeg = 0;
    let scaleX = 1;
    let scaleY = 1;
    let textScale = 1;
    let textRotationDeg = 0;

    switch (motionPreset) {
      case 'bow': {
        translateX = 2 * envelope * Math.sin(2 * Math.PI * progress);
        translateY = 13 * envelope;
        rotationDeg = -4 * envelope * Math.sin(Math.PI * progress);
        scaleX = 1 + 0.08 * envelope;
        scaleY = 1 - 0.13 * envelope;
        break;
      }
      case 'shake': {
        const shake = envelope * Math.sin(6 * Math.PI * progress);
        translateX = 9 * shake;
        translateY = 2 * envelope * Math.cos(6 * Math.PI * progress);
        rotationDeg = -7 * shake;
        scaleX = 1 + 0.06 * envelope * Math.cos(6 * Math.PI * progress);
        scaleY = 1 - 0.04 * envelope * Math.cos(6 * Math.PI * progress);
        break;
      }
      case 'laugh': {
        const laugh = envelope * Math.sin(4 * Math.PI * progress);
        translateX = 3 * laugh;
        translateY = -7 * envelope * Math.abs(Math.sin(4 * Math.PI * progress));
        rotationDeg = 5 * laugh;
        scaleX = 1 + 0.09 * envelope * Math.cos(4 * Math.PI * progress);
        scaleY = 1 - 0.07 * envelope * Math.cos(4 * Math.PI * progress);
        break;
      }
      case 'impact': {
        const hit = envelope * Math.sin(4 * Math.PI * progress);
        translateX = 5 * hit;
        translateY = -10 * envelope * Math.abs(Math.cos(2 * Math.PI * progress));
        rotationDeg = 6 * hit;
        scaleX = 1 + 0.18 * envelope * Math.abs(Math.cos(2 * Math.PI * progress));
        scaleY = 1 - 0.12 * envelope * Math.abs(Math.cos(2 * Math.PI * progress));
        textScale = 1 + 0.2 * envelope * Math.abs(Math.cos(2 * Math.PI * progress));
        textRotationDeg = -4 * hit;
        break;
      }
    }

    return {
      delayMs: (baseUnits + (index < remainder ? 1 : 0)) * 10,
      poseIndex: poseSequence[index],
      translateX: round(translateX),
      translateY: round(translateY),
      rotationDeg: round(rotationDeg),
      scaleX: round(scaleX),
      scaleY: round(scaleY),
      textScale: round(textScale),
      textRotationDeg: round(textRotationDeg),
    };
  });
}
