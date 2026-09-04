import { createHash } from 'node:crypto';

import sharp from 'sharp';

import type { PrototypeManifestItem } from './prototypeManifest.js';

const OUTPUT_SIZE = 240;
const MAX_BYTES = 250 * 1024;
const EFFECTIVE_ALPHA_THRESHOLD = 8;
const EDGE_MARGIN = 1;
const GIF_DURATION_TOLERANCE_MS = 10;

export interface PrototypeAuditIssue {
  id: string;
  field: string;
  expected: string | number | boolean | null;
  actual: string | number | boolean | null;
  message: string;
}

export interface PrototypeFrameAuditMetadata {
  index: number;
  effectiveAlphaPixels: number;
  alphaBounds: {
    left: number;
    top: number;
    right: number;
    bottom: number;
  } | null;
  sha256: string;
}

export interface PrototypeGifAuditMetadata {
  format: string | null;
  width: number | null;
  height: number | null;
  pageHeight: number | null;
  pages: number;
  loop: number | null;
  delays: number[];
  durationMs: number;
  bytes: number;
  sha256: string;
  frames: PrototypeFrameAuditMetadata[];
  firstFrameSha256: string | null;
  lastFrameSha256: string | null;
  loopClosed: boolean | null;
}

export interface PrototypeGifAuditResult {
  id: string;
  metadata: PrototypeGifAuditMetadata;
  issues: PrototypeAuditIssue[];
}

function addIssue(
  issues: PrototypeAuditIssue[],
  item: PrototypeManifestItem,
  field: string,
  expected: PrototypeAuditIssue['expected'],
  actual: PrototypeAuditIssue['actual'],
  message: string,
): void {
  issues.push({ id: item.id, field, expected, actual, message });
}

/** 对单个最终 GIF 进行可机器读取的格式、动画和透明边界审计。 */
export async function auditPrototypeGif(
  gif: Buffer,
  item: PrototypeManifestItem,
): Promise<PrototypeGifAuditResult> {
  const issues: PrototypeAuditIssue[] = [];
  const digest = createHash('sha256').update(gif).digest('hex');
  let imageMetadata: Awaited<ReturnType<ReturnType<typeof sharp>['metadata']>>;

  try {
    imageMetadata = await sharp(gif, { animated: true }).metadata();
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    addIssue(issues, item, 'format', 'gif', null, `无法解析 GIF：${detail}`);
    return {
      id: item.id,
      metadata: {
        format: null,
        width: null,
        height: null,
        pageHeight: null,
        pages: 0,
        loop: null,
        delays: [],
        durationMs: 0,
        bytes: gif.length,
        sha256: digest,
        frames: [],
        firstFrameSha256: null,
        lastFrameSha256: null,
        loopClosed: null,
      },
      issues,
    };
  }

  const format = imageMetadata.format ?? null;
  const width = imageMetadata.width ?? null;
  const pages = imageMetadata.pages ?? 1;
  const pageHeight = imageMetadata.pageHeight ?? imageMetadata.height ?? null;
  const height = pageHeight;
  const delays = Array.isArray(imageMetadata.delay) ? imageMetadata.delay : [];
  const durationMs = delays.reduce((total, delay) => total + delay, 0);
  const loop = imageMetadata.loop ?? null;

  if (format !== 'gif') {
    addIssue(issues, item, 'format', 'gif', format, `样板 ${item.id} 必须是 GIF`);
  }
  const dimensionsValid = width === OUTPUT_SIZE
    && height === OUTPUT_SIZE
    && pageHeight === OUTPUT_SIZE
    && imageMetadata.height === pageHeight * pages;
  if (!dimensionsValid) {
    addIssue(
      issues,
      item,
      'dimensions',
      `${OUTPUT_SIZE}x${OUTPUT_SIZE}, pageHeight=${OUTPUT_SIZE}`,
      `${width ?? '?'}x${height ?? '?'}, pageHeight=${pageHeight ?? '?'}`,
      `样板 ${item.id} 每帧必须是 240×240`,
    );
  }
  if (pages !== item.frameCount) {
    addIssue(issues, item, 'pages', item.frameCount, pages, `样板 ${item.id} 帧数与清单不一致`);
  }
  if (loop !== 0) {
    addIssue(issues, item, 'loop', 0, loop, `样板 ${item.id} 必须无限循环`);
  }
  if (delays.length !== pages || delays.some((delay) => !Number.isFinite(delay) || delay <= 0)) {
    addIssue(
      issues,
      item,
      'delays',
      `${pages} 个正延时`,
      JSON.stringify(delays),
      `样板 ${item.id} 所有帧延时都必须大于 0`,
    );
  }
  if (Math.abs(durationMs - item.durationMs) > GIF_DURATION_TOLERANCE_MS) {
    addIssue(
      issues,
      item,
      'durationMs',
      item.durationMs,
      durationMs,
      `样板 ${item.id} 总时长超出 GIF 10ms 量化容差`,
    );
  }
  if (gif.length > MAX_BYTES) {
    addIssue(issues, item, 'bytes', MAX_BYTES, gif.length, `样板 ${item.id} 超过 250KB`);
  }

  const frames: PrototypeFrameAuditMetadata[] = [];
  if (format === 'gif') {
    try {
      const decoded = await sharp(gif, { animated: true })
        .ensureAlpha()
        .raw()
        .toBuffer({ resolveWithObject: true });
      const decodedPageHeight = decoded.info.pageHeight ?? decoded.info.height / pages;
      const bytesPerFrame = decoded.info.width * decodedPageHeight * decoded.info.channels;

      for (let index = 0; index < pages; index += 1) {
        const start = index * bytesPerFrame;
        const frame = decoded.data.subarray(start, start + bytesPerFrame);
        let minX = decoded.info.width;
        let minY = decodedPageHeight;
        let maxX = -1;
        let maxY = -1;
        let effectiveAlphaPixels = 0;
        for (let y = 0; y < decodedPageHeight; y += 1) {
          for (let x = 0; x < decoded.info.width; x += 1) {
            const alphaOffset = (y * decoded.info.width + x) * decoded.info.channels + 3;
            if (frame[alphaOffset] < EFFECTIVE_ALPHA_THRESHOLD) continue;
            effectiveAlphaPixels += 1;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
          }
        }
        const alphaBounds = effectiveAlphaPixels === 0 ? null : {
          left: minX,
          top: minY,
          right: decoded.info.width - 1 - maxX,
          bottom: decodedPageHeight - 1 - maxY,
        };
        const frameDigest = createHash('sha256').update(frame).digest('hex');
        frames.push({ index, effectiveAlphaPixels, alphaBounds, sha256: frameDigest });

        if (effectiveAlphaPixels === 0) {
          addIssue(
            issues,
            item,
            `frames[${index}].effectiveAlpha`,
            '>0',
            0,
            `样板 ${item.id} 第 ${index + 1} 帧没有有效 alpha 像素`,
          );
        } else if (alphaBounds !== null && Math.min(
          alphaBounds.left,
          alphaBounds.top,
          alphaBounds.right,
          alphaBounds.bottom,
        ) < EDGE_MARGIN) {
          addIssue(
            issues,
            item,
            `frames[${index}].alphaBounds`,
            `>=${EDGE_MARGIN}px`,
            JSON.stringify(alphaBounds),
            `样板 ${item.id} 第 ${index + 1} 帧有效像素触边`,
          );
        }
      }
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      addIssue(issues, item, 'frames', '可解码的 RGBA 帧', null, `无法解码样板 ${item.id} 帧：${detail}`);
    }
  }

  const firstFrameSha256 = frames[0]?.sha256 ?? null;
  const lastFrameSha256 = frames.at(-1)?.sha256 ?? null;
  const loopClosed = firstFrameSha256 === null || lastFrameSha256 === null
    ? null
    : firstFrameSha256 === lastFrameSha256;
  if (loopClosed === false) {
    addIssue(
      issues,
      item,
      'loopClosure',
      true,
      false,
      `样板 ${item.id} 首尾姿势不一致，循环会跳帧`,
    );
  }

  return {
    id: item.id,
    metadata: {
      format,
      width,
      height,
      pageHeight,
      pages,
      loop,
      delays,
      durationMs,
      bytes: gif.length,
      sha256: digest,
      frames,
      firstFrameSha256,
      lastFrameSha256,
      loopClosed,
    },
    issues,
  };
}
