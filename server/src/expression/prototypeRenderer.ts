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
const BOTTOM_POSE_WIDTH = 170;
const BOTTOM_POSE_HEIGHT = 120;
const CENTER_POSE_SIZE = 216;
const MAX_TRANSFORMED_POSE_SIZE = 228;
const EFFECTIVE_ALPHA_THRESHOLD = 8;
const ALPHA_BBOX_PADDING = 4;
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
  item?: Pick<
    PrototypeManifestItem,
    'style' | 'keyword' | 'direction' | 'textPlacement'
  >;
  kinetic?: boolean;
  placement?: PrototypeTextPlacement;
  progress?: number;
}

interface TypographyStyle {
  fill: string;
  innerStroke: string;
  outerStroke: string;
  innerRadius: number;
  outerRadius: number;
  shadowX: number;
  shadowY: number;
  shadowBlur: number;
  baseScale: number;
  fontScale: number;
  baseRotation: number;
  centerOffsetY: number;
  rhythmScale: number;
  rhythmRotation: number;
  rhythmX: number;
  rhythmY: number;
  rhythmFrequency: number;
  rhythmPhase: number;
}

const TYPOGRAPHY_STYLES: Record<PrototypeManifestItem['style'], TypographyStyle> = {
  'original-character': {
    fill: '#FFFFFF', innerStroke: '#FF6B3D', outerStroke: '#2B1B13',
    innerRadius: 3, outerRadius: 7, shadowX: 1, shadowY: 4, shadowBlur: 2.5,
    baseScale: 1, fontScale: 1, baseRotation: -1, centerOffsetY: 0,
    rhythmScale: 0.025, rhythmRotation: 1.2, rhythmX: 1, rhythmY: -1,
    rhythmFrequency: 2, rhythmPhase: 0,
  },
  'ai-original-pet': {
    fill: '#FFF3D6', innerStroke: '#C9895A', outerStroke: '#3A281E',
    innerRadius: 2.5, outerRadius: 8, shadowX: -2, shadowY: 3, shadowBlur: 2,
    baseScale: 0.97, fontScale: 0.98, baseRotation: 1.2, centerOffsetY: 1,
    rhythmScale: 0.018, rhythmRotation: -1.5, rhythmX: -1.5, rhythmY: 0.5,
    rhythmFrequency: 1, rhythmPhase: 0.7,
  },
  'original-life-scene': {
    fill: '#FFE66D', innerStroke: '#E94235', outerStroke: '#171717',
    innerRadius: 2, outerRadius: 7, shadowX: 3, shadowY: 3, shadowBlur: 1.4,
    baseScale: 1.03, fontScale: 1.02, baseRotation: -2.2, centerOffsetY: -2,
    rhythmScale: 0.035, rhythmRotation: 1.8, rhythmX: 2, rhythmY: -1.5,
    rhythmFrequency: 2, rhythmPhase: 1.1,
  },
  '3d-plush': {
    fill: '#FFFDF8', innerStroke: '#FF8DB8', outerStroke: '#583874',
    innerRadius: 4, outerRadius: 8, shadowX: 2, shadowY: 5, shadowBlur: 3.8,
    baseScale: 0.98, fontScale: 0.97, baseRotation: 1.7, centerOffsetY: -1,
    rhythmScale: 0.022, rhythmRotation: -1, rhythmX: 1.2, rhythmY: 1,
    rhythmFrequency: 1, rhythmPhase: 2.2,
  },
  'hand-drawn': {
    fill: '#FFFFFF', innerStroke: '#2D8CFF', outerStroke: '#0E1B2A',
    innerRadius: 2, outerRadius: 5, shadowX: -3, shadowY: 3, shadowBlur: 0.8,
    baseScale: 0.94, fontScale: 0.95, baseRotation: -3, centerOffsetY: -3,
    rhythmScale: 0.015, rhythmRotation: 2.2, rhythmX: -2, rhythmY: -0.5,
    rhythmFrequency: 3, rhythmPhase: 0.4,
  },
  'fictional-live-action': {
    fill: '#FFFFFF', innerStroke: '#D9D9D9', outerStroke: '#050505',
    innerRadius: 1, outerRadius: 6, shadowX: 2, shadowY: 2, shadowBlur: 3,
    baseScale: 0.91, fontScale: 0.92, baseRotation: 0, centerOffsetY: 2,
    rhythmScale: 0.008, rhythmRotation: 0.5, rhythmX: 0.5, rhythmY: 0.4,
    rhythmFrequency: 1, rhythmPhase: 1.8,
  },
  'kinetic-typography': {
    fill: '#FFF15A', innerStroke: '#FF3E9D', outerStroke: '#4B1C86',
    innerRadius: 3.5, outerRadius: 8, shadowX: 3, shadowY: 5, shadowBlur: 2,
    baseScale: 1.04, fontScale: 1.04, baseRotation: -4, centerOffsetY: -4,
    rhythmScale: 0.06, rhythmRotation: 3.5, rhythmX: 3, rhythmY: -2,
    rhythmFrequency: 2, rhythmPhase: 0.2,
  },
  'internet-meme-grammar': {
    fill: '#E2FBFF', innerStroke: '#00B8D4', outerStroke: '#102A43',
    innerRadius: 2.5, outerRadius: 6, shadowX: -4, shadowY: 2, shadowBlur: 1.2,
    baseScale: 0.97, fontScale: 0.99, baseRotation: 2.6, centerOffsetY: -2,
    rhythmScale: 0.03, rhythmRotation: -2.8, rhythmX: -3, rhythmY: 1.5,
    rhythmFrequency: 3, rhythmPhase: 2.7,
  },
};

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

function resolveTypographyStyle(options: TextOverlayOptions): TypographyStyle {
  const item = options.item;
  const base = TYPOGRAPHY_STYLES[item?.style ?? 'original-character'];
  const resolved = { ...base };
  if (item?.keyword === '无语') {
    resolved.baseRotation -= 1.3;
    resolved.rhythmFrequency += 1;
    resolved.centerOffsetY += 1;
  } else if (item?.keyword === '笑死') {
    resolved.baseScale *= 1.035;
    resolved.rhythmScale += 0.012;
    resolved.rhythmRotation += 0.8;
  }
  if (item?.direction === 'kinetic-type') {
    resolved.rhythmScale *= 1.35;
    resolved.rhythmRotation *= 1.3;
    resolved.rhythmX *= 1.25;
  } else if (item?.direction === 'contrast-remix') {
    resolved.baseRotation *= -1;
    resolved.shadowX *= -1;
    resolved.rhythmY *= -1;
  } else if (item?.direction === 'pet-or-person') {
    resolved.rhythmY *= 1.25;
  }
  return resolved;
}

function calculateTypographyLayout(
  frame: PrototypeFramePlan,
  options: TextOverlayOptions,
  typography: TypographyStyle,
): {
  centerX: number;
  centerY: number;
  rotation: number;
  scale: number;
} {
  const progress = Math.min(1, Math.max(0, options.progress ?? 0));
  const envelope = Math.sin(Math.PI * progress);
  const phase = typography.rhythmPhase;
  const wave = Math.sin(2 * Math.PI * typography.rhythmFrequency * progress + phase);
  const crossWave = Math.sin(2 * Math.PI * (typography.rhythmFrequency + 0.5) * progress + phase);
  const kinetic = options.kinetic ?? options.item?.direction === 'kinetic-type';
  const kineticPulse = kinetic ? 0.07 * envelope * Math.abs(Math.sin(2 * Math.PI * progress)) : 0;
  const placement = options.placement ?? options.item?.textPlacement ?? 'bottom';
  const baseCenterY = placement === 'center' ? OUTPUT_SIZE / 2 : 178;
  return {
    centerX: OUTPUT_SIZE / 2 + envelope * typography.rhythmX * wave,
    centerY: baseCenterY + typography.centerOffsetY
      + envelope * typography.rhythmY * crossWave,
    rotation: frame.textRotationDeg + typography.baseRotation
      + envelope * typography.rhythmRotation * wave
      + (kinetic ? 2.5 * envelope * crossWave : 0),
    scale: frame.textScale * typography.baseScale
      * (1 + envelope * typography.rhythmScale * wave + kineticPulse),
  };
}

/** 构建逐帧文字层；文字永远由确定性 SVG 排版而不是由主视觉提供。 */
export function buildTextOverlaySvg(
  text: string,
  frame: PrototypeFramePlan,
  options: TextOverlayOptions = {},
): Buffer {
  const typography = resolveTypographyStyle(options);
  const layout = calculateTypographyLayout(frame, options, typography);
  const characterCount = Math.max(1, Array.from(text).length);
  const baseFontSize = characterCount <= 2 ? 54
    : characterCount <= 4 ? 44
      : characterCount <= 6 ? 35
        : 28;
  const fontSize = Math.round(baseFontSize * typography.fontScale);
  const safeText = escapeXml(text);

  return Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="240" height="240" viewBox="0 0 240 240">
      <defs>
        <filter id="textShadow" x="-30%" y="-40%" width="160%" height="190%">
          <feDropShadow dx="${typography.shadowX}" dy="${typography.shadowY}"
            stdDeviation="${typography.shadowBlur}" flood-color="#000000" flood-opacity="0.68"/>
        </filter>
      </defs>
      <g transform="translate(${layout.centerX.toFixed(3)} ${layout.centerY.toFixed(3)}) rotate(${layout.rotation.toFixed(3)}) scale(${layout.scale.toFixed(4)}) translate(-120 -${layout.centerY.toFixed(3)})"
         filter="url(#textShadow)">
        <text x="120" y="${(layout.centerY + 15).toFixed(3)}" text-anchor="middle"
          font-family="Droid Sans Fallback, Source Han Serif SC, sans-serif"
          font-size="${fontSize}" font-weight="900"
          fill="${typography.fill}" stroke="${typography.outerStroke}"
          stroke-width="${typography.outerRadius * 2}"
          stroke-linejoin="round" stroke-linecap="round"
          paint-order="stroke fill">${safeText}</text>
        <text x="120" y="${(layout.centerY + 12).toFixed(3)}" text-anchor="middle"
          font-family="Droid Sans Fallback, Source Han Serif SC, sans-serif"
          font-size="${fontSize}" font-weight="900"
          fill="${typography.fill}" stroke="${typography.innerStroke}"
          stroke-width="${typography.innerRadius * 2}"
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
  const typography = resolveTypographyStyle(options);
  const layout = calculateTypographyLayout(frame, options, typography);
  const characterCount = Math.max(1, Array.from(text).length);
  const baseFontSize = characterCount <= 2 ? 54
    : characterCount <= 4 ? 44
      : characterCount <= 6 ? 35
        : 28;
  const fontSize = Math.round(baseFontSize * typography.fontScale);
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
      background: typography.fill,
    },
  }).joinChannel(glyphAlpha).png().toBuffer();
  const x = OUTPUT_SIZE / 2 - renderedGlyph.info.width / 2;
  const y = layout.centerY - renderedGlyph.info.height / 2;
  const glyphUrl = `data:image/png;base64,${glyph.toString('base64')}`;
  const svg = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
      width="240" height="240" viewBox="0 0 240 240">
      <defs>
        <filter id="outlinedText" x="-45%" y="-70%" width="190%" height="240%"
          color-interpolation-filters="sRGB">
          <feGaussianBlur in="SourceAlpha" stdDeviation="${typography.shadowBlur}" result="shadowBlur"/>
          <feOffset in="shadowBlur" dx="${typography.shadowX}" dy="${typography.shadowY}" result="shadowOffset"/>
          <feFlood flood-color="#000000" flood-opacity="0.68" result="shadowColour"/>
          <feComposite in="shadowColour" in2="shadowOffset" operator="in" result="shadow"/>
          <feMorphology in="SourceAlpha" operator="dilate" radius="${typography.outerRadius}" result="outerMask"/>
          <feFlood flood-color="${typography.outerStroke}" result="outerColour"/>
          <feComposite in="outerColour" in2="outerMask" operator="in" result="outer"/>
          <feMorphology in="SourceAlpha" operator="dilate" radius="${typography.innerRadius}" result="innerMask"/>
          <feFlood flood-color="${typography.innerStroke}" result="innerColour"/>
          <feComposite in="innerColour" in2="innerMask" operator="in" result="inner"/>
          <feMerge>
            <feMergeNode in="shadow"/>
            <feMergeNode in="outer"/>
            <feMergeNode in="inner"/>
            <feMergeNode in="SourceGraphic"/>
          </feMerge>
        </filter>
      </defs>
      <g transform="translate(${layout.centerX.toFixed(3)} ${layout.centerY.toFixed(3)}) rotate(${layout.rotation.toFixed(3)}) scale(${layout.scale.toFixed(4)}) translate(-120 -${layout.centerY.toFixed(3)})">
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

interface AnalysedPose {
  oriented: Buffer;
  crop: { left: number; top: number; width: number; height: number };
}

async function analysePrototypePose(
  source: Buffer | string,
  item: PrototypeManifestItem,
  poseIndex: number,
): Promise<AnalysedPose> {
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
    let effectivePixels = 0;
    let allOpaque = true;
    for (let y = 0; y < info.height; y += 1) {
      for (let x = 0; x < info.width; x += 1) {
        const alpha = data[(y * info.width + x) * info.channels + 3];
        if (alpha !== 255) allOpaque = false;
        if (alpha > 0) nonTransparentPixels += 1;
        if (alpha < EFFECTIVE_ALPHA_THRESHOLD) continue;
        effectivePixels += 1;
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
    const alphaRatio = effectivePixels / (info.width * info.height);
    if (alphaRatio < 0.005 || alphaRatio > 0.9) {
      throw new Error(`姿势 alpha 占比异常（样板 ${item.id}，姿势 ${poseNumber}，占比 ${alphaRatio.toFixed(4)}）`);
    }

    const cropLeft = Math.max(0, minX - ALPHA_BBOX_PADDING);
    const cropTop = Math.max(0, minY - ALPHA_BBOX_PADDING);
    const cropRight = Math.min(info.width - 1, maxX + ALPHA_BBOX_PADDING);
    const cropBottom = Math.min(info.height - 1, maxY + ALPHA_BBOX_PADDING);
    return {
      oriented,
      crop: {
        left: cropLeft,
        top: cropTop,
        width: cropRight - cropLeft + 1,
        height: cropBottom - cropTop + 1,
      },
    };
  } catch (error) {
    if (error instanceof Error && error.message.includes(`样板 ${item.id}`)) throw error;
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`无法处理姿势（样板 ${item.id}，姿势 ${poseNumber}）：${detail}`, { cause: error });
  }
}

/** 组级规范化四个姿势：bottom 共用源像素尺度和底部锚点，center 保留完整构图。 */
export async function preparePrototypePoses(
  sources: Array<Buffer | string>,
  item: PrototypeManifestItem,
): Promise<Buffer[]> {
  if (sources.length !== 4) {
    throw new Error(`姿势源必须恰好 4 个（样板 ${item.id}）`);
  }
  const analysed = await Promise.all(sources.map((source, poseIndex) => (
    analysePrototypePose(source, item, poseIndex)
  )));
  if (item.textPlacement === 'center') {
    return Promise.all(analysed.map(({ oriented }) => sharp(oriented)
      .resize(CENTER_POSE_SIZE, CENTER_POSE_SIZE, {
        fit: 'contain',
        background: TRANSPARENT,
      })
      .png()
      .toBuffer()));
  }

  const maximumWidth = Math.max(...analysed.map(({ crop }) => crop.width));
  const maximumHeight = Math.max(...analysed.map(({ crop }) => crop.height));
  const groupScale = Math.min(
    BOTTOM_POSE_WIDTH / maximumWidth,
    BOTTOM_POSE_HEIGHT / maximumHeight,
  );
  return Promise.all(analysed.map(async ({ oriented, crop }) => {
    const width = Math.max(1, Math.round(crop.width * groupScale));
    const height = Math.max(1, Math.round(crop.height * groupScale));
    const scaled = await sharp(oriented)
      .extract(crop)
      .resize(width, height, { fit: 'fill' })
      .png()
      .toBuffer();
    return sharp({
      create: {
        width: BOTTOM_POSE_WIDTH,
        height: BOTTOM_POSE_HEIGHT,
        channels: 4,
        background: TRANSPARENT,
      },
    }).composite([{
      input: scaled,
      left: Math.round((BOTTOM_POSE_WIDTH - width) / 2),
      top: BOTTOM_POSE_HEIGHT - height,
    }]).png().toBuffer();
  }));
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
  const poseCenterY = item.textPlacement === 'center' ? OUTPUT_SIZE / 2 : 80;
  const top = Math.round(poseCenterY + plan.translateY - transformed.info.height / 2);
  const progress = frameIndex / (item.frameCount - 1);
  const effects = buildEffectsSvg(item.motionPreset, frameIndex, item.frameCount);
  const text = await rasterizeTextOverlay(item.text, plan, {
    item,
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
  const preparedPoses = await preparePrototypePoses(sources, item);
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
