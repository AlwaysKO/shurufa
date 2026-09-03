import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

import sharp from 'sharp';
import { describe, expect, it } from 'vitest';

import type { PrototypeManifestItem } from './prototypeManifest.js';
import {
  assertPrototypeFontAvailable,
  buildFramePlan,
  buildTextOverlaySvg,
  PROTOTYPE_FONT_PATH,
  rasterizeTextOverlay,
  renderPrototypeGif,
} from './prototypeRenderer.js';

function makeItem(overrides: Partial<PrototypeManifestItem> = {}): PrototypeManifestItem {
  return {
    id: 'thanks-render-01',
    keyword: '谢谢',
    text: '谢谢',
    style: 'kinetic-typography',
    direction: 'kinetic-type',
    sourceType: 'ai-original',
    prompt: '原创，无文字，无水印，无品牌，无现有角色',
    motionPreset: 'impact',
    frameCount: 10,
    durationMs: 947,
    masterFile: 'masters/thanks-render-01.png',
    ...overrides,
  };
}

async function createMaster(): Promise<Buffer> {
  const subject = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="360" height="160">
      <rect x="3" y="3" width="354" height="154" rx="30"
        fill="#00f06a" stroke="#042d19" stroke-width="6"/>
      <circle cx="130" cy="72" r="16" fill="#ffffff"/>
      <circle cx="230" cy="72" r="16" fill="#ffffff"/>
    </svg>
  `);
  return sharp({
    create: { width: 440, height: 260, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  }).composite([{ input: subject, left: 40, top: 30 }]).png().toBuffer();
}

async function alphaBounds(image: Buffer): Promise<{
  left: number;
  top: number;
  right: number;
  bottom: number;
  pixels: number;
}> {
  const { data, info } = await sharp(image).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let minX = info.width;
  let minY = info.height;
  let maxX = -1;
  let maxY = -1;
  let pixels = 0;
  for (let y = 0; y < info.height; y += 1) {
    for (let x = 0; x < info.width; x += 1) {
      if (data[(y * info.width + x) * info.channels + 3] === 0) continue;
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
      pixels += 1;
    }
  }
  return {
    left: minX,
    top: minY,
    right: info.width - 1 - maxX,
    bottom: info.height - 1 - maxY,
    pixels,
  };
}

describe('buildFramePlan', () => {
  it('按指定帧数生成动作计划', () => {
    expect(buildFramePlan('bow', 10, 900)).toHaveLength(10);
  });

  it('四种动作确定且彼此不同，并以相同姿态无缝循环', () => {
    const motions = ['bow', 'shake', 'laugh', 'impact'] as const;
    const plans = motions.map((motion) => buildFramePlan(motion, 13, 1_047));

    expect(new Set(plans.map((plan) => JSON.stringify(plan))).size).toBe(4);
    for (const [index, plan] of plans.entries()) {
      expect(plan).toEqual(buildFramePlan(motions[index], 13, 1_047));
      expect(plan.every(({ delayMs }) => delayMs > 0)).toBe(true);
      expect(Math.abs(plan.reduce((sum, frame) => sum + frame.delayMs, 0) - 1_047))
        .toBeLessThanOrEqual(5);

      const pose = ({ delayMs: _delayMs, ...frame }: (typeof plan)[number]) => frame;
      expect(pose(plan.at(0)!)).toEqual(pose(plan.at(-1)!));
      expect(plan.some((frame) => (
        (frame.translateX !== 0 || frame.translateY !== 0)
          && (frame.rotationDeg !== 0 || frame.scaleX !== frame.scaleY)
      ))).toBe(true);
      expect(Math.max(...plan.map(({ translateX }) => Math.abs(translateX))))
        .toBeGreaterThan(0);
      expect(Math.max(...plan.map(({ translateY }) => Math.abs(translateY))))
        .toBeGreaterThan(0);
      expect(Math.max(...plan.map(({ rotationDeg }) => Math.abs(rotationDeg))))
        .toBeGreaterThan(0);
      expect(Math.max(...plan.map(({ scaleX, scaleY }) => Math.abs(scaleX - scaleY))))
        .toBeGreaterThan(0);
    }
  });

  it('impact 对文字产生独立冲击动画，普通动作保持文字稳定', () => {
    const impact = buildFramePlan('impact', 12, 1_000);
    const bow = buildFramePlan('bow', 12, 1_000);

    expect(impact.some(({ textScale, textRotationDeg }) => (
      textScale !== 1 || textRotationDeg !== 0
    ))).toBe(true);
    expect(bow.every(({ textScale, textRotationDeg }) => (
      textScale === 1 && textRotationDeg === 0
    ))).toBe(true);
  });
});

describe('buildTextOverlaySvg', () => {
  it('转义文字并使用高对比中文字体、圆角描边和阴影', () => {
    const frame = buildFramePlan('bow', 10, 900)[0];
    const svg = buildTextOverlaySvg('<谢谢&“你”>', frame).toString();

    expect(svg).toContain('&lt;谢谢&amp;“你”&gt;');
    expect(svg).not.toContain('<谢谢');
    expect(svg).toContain('Droid Sans Fallback');
    expect(svg).toContain('Source Han Serif SC');
    expect(svg).toContain('font-weight="900"');
    expect(svg).toContain('stroke-linejoin="round"');
    expect(svg).toContain('<filter');
  });

  it('kinetic-type 的文字冲击保持确定性并在中间帧发生变化', () => {
    const frame = buildFramePlan('bow', 10, 900)[4];
    const first = buildTextOverlaySvg('笑死', frame, { kinetic: true, progress: 0 });
    const middle = buildTextOverlaySvg('笑死', frame, { kinetic: true, progress: 0.5 });

    expect(first.equals(buildTextOverlaySvg('笑死', frame, { kinetic: true, progress: 0 })))
      .toBe(true);
    expect(first.equals(middle)).toBe(false);
  });
});

describe('rasterizeTextOverlay', () => {
  it('极端 impact+kinetic 全部帧的文字四边至少保留 4px 透明余量', async () => {
    const plan = buildFramePlan('impact', 20, 2_000);

    for (const [index, frame] of plan.entries()) {
      const image = await rasterizeTextOverlay('谢谢', frame, {
        kinetic: true,
        progress: index / (plan.length - 1),
      });
      const bounds = await alphaBounds(image);
      expect(Math.min(bounds.left, bounds.top, bounds.right, bounds.bottom),
        `第 ${index} 帧发生裁切`).toBeGreaterThanOrEqual(4);
    }
  });

  it('从仓库固定字体栅格化中文，而不是依赖系统回退方框', async () => {
    expect(PROTOTYPE_FONT_PATH).toBe(resolve(
      process.cwd(),
      '../assets/expression/fonts/DroidSansFallbackFull.ttf',
    ));
    const font = await readFile(PROTOTYPE_FONT_PATH);
    expect(font.length).toBeGreaterThan(1_000_000);

    const frame = buildFramePlan('bow', 10, 900)[0];
    const chinese = await rasterizeTextOverlay('谢谢', frame);
    const replacementBoxes = await rasterizeTextOverlay('□□', frame);
    const bounds = await alphaBounds(chinese);
    const { data, info } = await sharp(chinese).ensureAlpha().raw()
      .toBuffer({ resolveWithObject: true });
    let whiteGlyphPixels = 0;
    for (let offset = 0; offset < data.length; offset += info.channels) {
      if (data[offset] > 240 && data[offset + 1] > 240
        && data[offset + 2] > 240 && data[offset + 3] > 240) {
        whiteGlyphPixels += 1;
      }
    }

    expect(bounds.pixels).toBeGreaterThan(500);
    expect(whiteGlyphPixels).toBeGreaterThan(100);
    expect(chinese.equals(replacementBoxes)).toBe(false);
  });

  it('固定字体缺失时立即抛出包含样板 ID 和路径的错误', async () => {
    const missingPath = join(tmpdir(), 'missing-prototype-font.ttf');
    await expect(assertPrototypeFontAvailable(missingPath, 'thanks-render-01'))
      .rejects.toThrow(/固定字体缺失.*thanks-render-01.*missing-prototype-font\.ttf/);
  });
});

describe('renderPrototypeGif', () => {
  it.each(['bow', 'shake', 'laugh', 'impact'] as const)(
    '%s 把宽幅透明 PNG 渲染为可循环的 240px 多帧 GIF，并保留实际动作',
    async (motionPreset) => {
      const item = makeItem({
        motionPreset,
        direction: 'core-performance',
        style: 'original-character',
      });
      const output = await renderPrototypeGif({ master: await createMaster(), item });
      const metadata = await sharp(output).metadata();
      const animatedMetadata = await sharp(output, { animated: true }).metadata();

      expect(metadata.format).toBe('gif');
      expect(metadata.width).toBe(240);
      expect(metadata.height).toBe(240);
      expect(metadata.pages).toBe(item.frameCount);
      expect(metadata.loop).toBe(0);
      expect(metadata.delay).toHaveLength(item.frameCount);
      expect(metadata.delay!.every((delay) => delay > 0)).toBe(true);
      expect(Math.abs(metadata.delay!.reduce((sum, delay) => sum + delay, 0) - item.durationMs))
        .toBeLessThanOrEqual(Math.max(5, item.frameCount));
      expect(animatedMetadata.pages).toBe(item.frameCount);
      expect(animatedMetadata.pageHeight).toBe(240);

      const pixels = await sharp(output, { animated: true }).ensureAlpha().raw().toBuffer();
      const pageBytes = 240 * 240 * 4;
      const middleFrame = Math.floor(item.frameCount / 2);
      expect(pixels.subarray(0, pageBytes).equals(
        pixels.subarray(pageBytes * middleFrame, pageBytes * (middleFrame + 1)),
      )).toBe(false);
    },
  );

  it('masterPath 与 Buffer 输入产生相同结果', async () => {
    const item = makeItem({ motionPreset: 'laugh', direction: 'pet-or-person' });
    const master = await createMaster();
    const directory = await mkdtemp(join(tmpdir(), 'prototype-renderer-'));
    const masterPath = join(directory, 'master.png');
    try {
      await writeFile(masterPath, master);
      const fromBuffer = await renderPrototypeGif({ master, item });
      const fromPath = await renderPrototypeGif({ masterPath, item });
      expect(fromPath.equals(fromBuffer)).toBe(true);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it.each([
    [{ frameCount: 9 }, 'frameCount'],
    [{ frameCount: 21 }, 'frameCount'],
    [{ durationMs: 799 }, 'durationMs'],
    [{ durationMs: 2_001 }, 'durationMs'],
  ] as const)('防御性拒绝超出清单范围的参数 %o', async (overrides, field) => {
    await expect(renderPrototypeGif({
      master: await createMaster(),
      item: makeItem(overrides),
    })).rejects.toThrow(new RegExp(`${field}.*thanks-render-01`));
  });

  it('逐帧处理失败时报告样板 ID 和帧序号', async () => {
    await expect(renderPrototypeGif({
      master: await createMaster(),
      item: makeItem({ text: '' }),
    })).rejects.toThrow(/渲染样板 thanks-render-01 第 \d+ 帧失败/);
  });

  it('不可读或非 PNG 主视觉抛出包含样板 ID 的可定位错误', async () => {
    await expect(renderPrototypeGif({ master: Buffer.from('not an image'), item: makeItem() }))
      .rejects.toThrow(/无法读取主视觉.*thanks-render-01/);

    const jpeg = await sharp({
      create: { width: 20, height: 20, channels: 3, background: '#fff' },
    }).jpeg().toBuffer();
    await expect(renderPrototypeGif({ master: jpeg, item: makeItem() }))
      .rejects.toThrow(/PNG.*thanks-render-01/);
  });
});
