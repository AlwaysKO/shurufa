import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import sharp from 'sharp';
import { describe, expect, it } from 'vitest';

import type { PrototypeManifestItem } from './prototypeManifest.js';
import { auditPrototypeGif } from './prototypeAudit.js';

const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 } as const;

function makeItem(overrides: Partial<PrototypeManifestItem> = {}): PrototypeManifestItem {
  return {
    id: 'thanks-audit-sample',
    keyword: '谢谢',
    text: '谢谢',
    style: 'original-character',
    direction: 'core-performance',
    sourceType: 'ai-original',
    prompt: '完全原创，原创，无文字，无水印，无品牌，无现有角色',
    motionPreset: 'bow',
    frameCount: 10,
    durationMs: 1_000,
    masterFile: 'masters/thanks-audit-sample.png',
    poseFiles: Array.from(
      { length: 4 },
      (_, index) => `poses/thanks-audit-sample/pose-0${index + 1}.png`,
    ),
    textPlacement: 'bottom',
    ...overrides,
  };
}

interface GifFixtureOptions {
  width?: number;
  height?: number;
  frameCount?: number;
  delays?: number[];
  loop?: number;
  touchingEdge?: boolean;
  emptyFrame?: number;
  openLoop?: boolean;
  staticFrames?: boolean;
  microFlash?: boolean;
  finalOffset?: number;
}

async function makeGif({
  width = 240,
  height = 240,
  frameCount = 10,
  delays = Array.from({ length: frameCount }, () => 100),
  loop = 0,
  touchingEdge = false,
  emptyFrame,
  openLoop = false,
  staticFrames = false,
  microFlash = false,
  finalOffset,
}: GifFixtureOptions = {}): Promise<Buffer> {
  const motionOffsets = [0, 2, 4, 6, 8, 10, 8, 6, 2, 0];
  const frames = await Promise.all(Array.from({ length: frameCount }, async (_, index) => {
    const isEmpty = index === emptyFrame;
    const isLast = index === frameCount - 1;
    const offset = staticFrames ? 0
      : isLast && finalOffset !== undefined ? finalOffset
        : isLast && openLoop ? 24
          : motionOffsets[index % motionOffsets.length];
    const x = touchingEdge ? 0 : 20 + offset;
    const overlays = isEmpty ? [] : [{
      input: Buffer.from(
        `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">`
        + `<rect x="${x}" y="20" width="72" height="72" rx="18" fill="#ff6038"/>`
        + (microFlash && index % 2 === 1
          ? '<rect x="150" y="150" width="1" height="1" fill="#36a9ff"/>'
          : '')
        + '</svg>',
      ),
      left: 0,
      top: 0,
    }];
    return sharp({
      create: { width, height, channels: 4, background: TRANSPARENT },
    }).composite(overlays).png().toBuffer();
  }));

  return sharp({
    create: {
      width,
      height: height * frameCount,
      pageHeight: height,
      channels: 4,
      background: TRANSPARENT,
    },
  }).composite(frames.map((input, index) => ({ input, left: 0, top: index * height })))
    .gif({ loop, delay: delays, keepDuplicateFrames: true })
    .toBuffer();
}

function expectIssue(result: Awaited<ReturnType<typeof auditPrototypeGif>>, field: string): void {
  expect(result.issues).toContainEqual(expect.objectContaining({
    id: 'thanks-audit-sample',
    field,
  }));
}

describe('auditPrototypeGif', () => {
  it('合格 GIF 返回完整元数据且没有问题', async () => {
    const gif = await makeGif();

    const result = await auditPrototypeGif(gif, makeItem());

    expect(result.issues).toEqual([]);
    expect(result.metadata).toMatchObject({
      format: 'gif',
      width: 240,
      height: 240,
      pageHeight: 240,
      pages: 10,
      loop: 0,
      delays: Array.from({ length: 10 }, () => 100),
      durationMs: 1_000,
      bytes: gif.length,
      sha256: createHash('sha256').update(gif).digest('hex'),
    });
    expect(result.metadata.motion.uniqueFrameCount).toBeGreaterThanOrEqual(4);
    expect(result.metadata.motion.meaningfulTransitionCount).toBeGreaterThanOrEqual(3);
    expect(result.metadata.loopClosure.passed).toBe(true);
    expect(result.metadata.frames).toHaveLength(10);
    expect(result.metadata.frames.every(({ effectiveAlphaPixels }) => effectiveAlphaPixels > 0))
      .toBe(true);
  });

  it('拒绝非 GIF 格式', async () => {
    const png = await sharp({
      create: { width: 240, height: 240, channels: 4, background: TRANSPARENT },
    }).png().toBuffer();

    const result = await auditPrototypeGif(png, makeItem());

    expectIssue(result, 'format');
  });

  it('拒绝非 240×240 画布或错误 pageHeight', async () => {
    const gif = await makeGif({ width: 239, height: 241 });

    const result = await auditPrototypeGif(gif, makeItem());

    expectIssue(result, 'dimensions');
  });

  it('拒绝与清单不同的帧数', async () => {
    const gif = await makeGif({ frameCount: 9 });

    const result = await auditPrototypeGif(gif, makeItem());

    expectIssue(result, 'pages');
  });

  it('拒绝非无限循环或非正延时', async () => {
    const wrongLoop = await auditPrototypeGif(await makeGif({ loop: 2 }), makeItem());
    expectIssue(wrongLoop, 'loop');

    const zeroDelay = await auditPrototypeGif(
      await makeGif({ delays: [0, ...Array.from({ length: 9 }, () => 100)] }),
      makeItem(),
    );
    expectIssue(zeroDelay, 'delays');
  });

  it('拒绝超出 GIF 10ms 量化容差的总时长', async () => {
    const gif = await makeGif({ delays: Array.from({ length: 10 }, () => 90) });

    const result = await auditPrototypeGif(gif, makeItem());

    expectIssue(result, 'durationMs');
  });

  it('拒绝超过 250KB 的 GIF', async () => {
    const oversized = Buffer.concat([await makeGif(), Buffer.alloc(250 * 1024)]);

    const result = await auditPrototypeGif(oversized, makeItem());

    expectIssue(result, 'bytes');
  });

  it('拒绝任意帧有效 alpha 为空或有效像素触边', async () => {
    const empty = await auditPrototypeGif(await makeGif({ emptyFrame: 4 }), makeItem());
    expectIssue(empty, 'frames[4].effectiveAlpha');

    const touching = await auditPrototypeGif(await makeGif({ touchingEdge: true }), makeItem());
    expectIssue(touching, 'frames[0].alphaBounds');
  });

  it('拒绝首尾姿势不闭合的循环', async () => {
    const gif = await makeGif({ openLoop: true });

    const result = await auditPrototypeGif(gif, makeItem());

    expectIssue(result, 'loopClosure');
  });

  it('拒绝伪装成多帧动画的静态 GIF', async () => {
    const result = await auditPrototypeGif(await makeGif({ staticFrames: true }), makeItem());

    expectIssue(result, 'motion.uniqueFrameCount');
    expectIssue(result, 'motion.meaningfulTransitions');
  });

  it('拒绝仅靠一像素闪点制造的虚假运动', async () => {
    const result = await auditPrototypeGif(
      await makeGif({ staticFrames: true, microFlash: true }),
      makeItem(),
    );

    expectIssue(result, 'motion.uniqueFrameCount');
    expectIssue(result, 'motion.meaningfulTransitions');
  });

  it('使用相对感知差异容许轻微首尾差异但拒绝明显突跳', async () => {
    const gentle = await auditPrototypeGif(await makeGif({ finalOffset: 1 }), makeItem());
    expect(gentle.metadata.firstFrameSha256).not.toBe(gentle.metadata.lastFrameSha256);
    expect(gentle.metadata.loopClosure.boundaryDifferenceRatio).toBeGreaterThan(0);
    expect(gentle.metadata.loopClosure.passed).toBe(true);
    expect(gentle.issues.some(({ field }) => field === 'loopClosure')).toBe(false);

    const abrupt = await auditPrototypeGif(await makeGif({ openLoop: true }), makeItem());
    expect(abrupt.metadata.loopClosure.passed).toBe(false);
    expectIssue(abrupt, 'loopClosure');
  });
});

describe('动态样板审计报告', () => {
  it('逐项保留最终 GIF 的格式、尺寸和循环元数据', () => {
    const reportPath = fileURLToPath(new URL(
      '../../../artifacts/expression-prototypes/report.json',
      import.meta.url,
    ));
    const report = JSON.parse(readFileSync(reportPath, 'utf8')) as {
      items: Array<Record<string, unknown>>;
    };

    expect(report.items).toHaveLength(12);
    for (const item of report.items) {
      expect(item).toMatchObject({
        format: 'gif',
        width: 240,
        height: 240,
        pageHeight: 240,
        loop: 0,
        motion: {
          uniqueFrameCount: expect.any(Number),
          meaningfulTransitionCount: expect.any(Number),
        },
        loopClosure: {
          boundaryDifferenceRatio: expect.any(Number),
          internalMedianDifferenceRatio: expect.any(Number),
          allowedBoundaryDifferenceRatio: expect.any(Number),
          passed: true,
        },
      });
    }
  });
});
