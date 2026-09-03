import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

import sharp from 'sharp';
import { describe, expect, it } from 'vitest';

import type { PrototypeManifestItem } from './prototypeManifest.js';
import {
  assertPrototypeFontAvailable,
  buildFramePlan,
  buildPoseSequence,
  buildTextOverlaySvg,
  preparePrototypePose,
  PROTOTYPE_FONT_PATH,
  rasterizeTextOverlay,
  renderPrototypeGif,
  type RenderPrototypeGifOptions,
} from './prototypeRenderer.js';

function makeItem(overrides: Partial<PrototypeManifestItem> = {}): PrototypeManifestItem {
  return {
    id: 'thanks-render-01',
    keyword: '谢谢',
    text: '谢谢',
    style: 'original-character',
    direction: 'core-performance',
    sourceType: 'ai-original',
    prompt: '原创，无文字，无水印，无品牌，无现有角色',
    motionPreset: 'impact',
    frameCount: 10,
    durationMs: 947,
    masterFile: 'masters/thanks-render-01.png',
    poseFiles: [
      'poses/thanks-render-01/pose-01.png',
      'poses/thanks-render-01/pose-02.png',
      'poses/thanks-render-01/pose-03.png',
      'poses/thanks-render-01/pose-04.png',
    ],
    textPlacement: 'bottom',
    ...overrides,
  };
}

const POSE_COLOURS = [
  { r: 0, g: 200, b: 255 },
  { r: 0, g: 240, b: 106 },
  { r: 130, g: 55, b: 245 },
  { r: 255, g: 210, b: 0 },
] as const;

async function createPose(index: number): Promise<Buffer> {
  const colour = POSE_COLOURS[index];
  const subject = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="150" height="110">
      <rect x="4" y="4" width="142" height="102" rx="${16 + index * 5}"
        fill="rgb(${colour.r},${colour.g},${colour.b})" stroke="#172033" stroke-width="8"/>
      <circle cx="${42 + index * 6}" cy="45" r="12" fill="#ffffff"/>
      <path d="M45 78 Q75 ${92 - index * 5} 105 78" fill="none" stroke="#172033" stroke-width="7"/>
    </svg>
  `);
  return sharp({
    create: {
      width: 360,
      height: 260,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  }).composite([{ input: subject, left: 55 + index * 25, top: 60 }]).png().toBuffer();
}

async function createPoses(): Promise<Buffer[]> {
  return Promise.all([0, 1, 2, 3].map(createPose));
}

async function alphaBounds(image: Buffer): Promise<{
  left: number;
  top: number;
  right: number;
  bottom: number;
  pixels: number;
  width: number;
  height: number;
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
    width: info.width,
    height: info.height,
  };
}

describe('buildFramePlan', () => {
  it('按指定帧数生成动作计划', () => {
    expect(buildFramePlan('bow', 10, 900)).toHaveLength(10);
  });

  it.each([10, 13, 20])('把 %i 帧确定映射为完整的 0→1→2→3→2→1→0 姿势循环', (frameCount) => {
    const sequence = buildPoseSequence(frameCount);
    const collapsed = sequence.filter((poseIndex, index) => poseIndex !== sequence[index - 1]);

    expect(sequence).toHaveLength(frameCount);
    expect(collapsed).toEqual([0, 1, 2, 3, 2, 1, 0]);
    expect(buildFramePlan('bow', frameCount, 1_200).map(({ poseIndex }) => poseIndex))
      .toEqual(sequence);
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
  it.each(['bottom', 'center'] as const)(
    '%s 布局在极端 impact+kinetic 全部帧的文字四边至少保留 4px 透明余量',
    async (placement) => {
      const plan = buildFramePlan('impact', 20, 2_000);

      for (const [index, frame] of plan.entries()) {
        const image = await rasterizeTextOverlay('谢谢', frame, {
          kinetic: true,
          placement,
          progress: index / (plan.length - 1),
        });
        const bounds = await alphaBounds(image);
        expect(Math.min(bounds.left, bounds.top, bounds.right, bounds.bottom),
          `第 ${index} 帧发生裁切`).toBeGreaterThanOrEqual(4);
      }
    },
  );

  it('center 文字位于画布中央，bottom 文字位于底部安全区', async () => {
    const frame = buildFramePlan('bow', 10, 900)[0];
    const center = await alphaBounds(await rasterizeTextOverlay('谢谢', frame, {
      placement: 'center',
    }));
    const bottom = await alphaBounds(await rasterizeTextOverlay('谢谢', frame, {
      placement: 'bottom',
    }));

    expect(center.top).toBeLessThan(bottom.top - 40);
    expect(center.bottom).toBeGreaterThan(bottom.bottom + 40);
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

describe('preparePrototypePose', () => {
  it('bottom 布局按 alpha bbox 去除透明留白并放大主体', async () => {
    const source = await sharp({
      create: {
        width: 400,
        height: 400,
        channels: 4,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      },
    }).composite([{
      input: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="40" height="80"><rect width="40" height="80" fill="#00ff00"/></svg>'),
      left: 7,
      top: 11,
    }]).png().toBuffer();

    const prepared = await preparePrototypePose(source, makeItem(), 0);
    const bounds = await alphaBounds(prepared);
    expect(Math.max(
      bounds.width - bounds.left - bounds.right,
      bounds.height - bounds.top - bounds.bottom,
    ))
      .toBeGreaterThanOrEqual(100);
  });

  it('center 布局保留完整画布中的左右相对位置', async () => {
    const makeSidePose = async (left: number) => sharp({
      create: {
        width: 300,
        height: 300,
        channels: 4,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      },
    }).composite([{
      input: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="60" height="120"><rect width="60" height="120" fill="#00ff00"/></svg>'),
      left,
      top: 90,
    }]).png().toBuffer();
    const item = makeItem({
      direction: 'kinetic-type',
      textPlacement: 'center',
    });
    const left = await alphaBounds(await preparePrototypePose(await makeSidePose(20), item, 0));
    const right = await alphaBounds(await preparePrototypePose(await makeSidePose(220), item, 1));
    const leftCenter = left.left + (left.width - left.left - left.right) / 2;
    const rightCenter = right.left + (right.width - right.left - right.right) / 2;

    expect([left.width, left.height, right.width, right.height]).toEqual([216, 216, 216, 216]);
    expect(leftCenter).toBeLessThan(left.width * 0.38);
    expect(rightCenter).toBeGreaterThan(right.width * 0.62);
  });

  it('拒绝完全不透明、全透明和 alpha 占比异常的姿势并定位序号', async () => {
    const opaque = await sharp({
      create: { width: 100, height: 100, channels: 4, background: { r: 20, g: 30, b: 40, alpha: 1 } },
    }).png().toBuffer();
    const empty = await sharp({
      create: { width: 100, height: 100, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
    }).png().toBuffer();
    const tiny = await sharp({
      create: { width: 200, height: 200, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
    }).composite([{
      input: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><rect width="2" height="2" fill="#fff"/></svg>'),
      left: 10,
      top: 10,
    }]).png().toBuffer();

    await expect(preparePrototypePose(opaque, makeItem(), 0))
      .rejects.toThrow(/完全不透明.*thanks-render-01.*姿势 1/);
    await expect(preparePrototypePose(empty, makeItem(), 1))
      .rejects.toThrow(/全透明.*thanks-render-01.*姿势 2/);
    await expect(preparePrototypePose(tiny, makeItem(), 2))
      .rejects.toThrow(/alpha 占比异常.*thanks-render-01.*姿势 3/);
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
        textPlacement: 'bottom',
      });
      const output = await renderPrototypeGif({ masters: await createPoses(), item });
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

  it('真实 GIF 的主体帧严格使用全部四个姿势', async () => {
    const item = makeItem({ motionPreset: 'bow' });
    const output = await renderPrototypeGif({ masters: await createPoses(), item });
    const { data, info } = await sharp(output, { animated: true }).ensureAlpha().raw()
      .toBuffer({ resolveWithObject: true });
    const pageBytes = 240 * 240 * info.channels;
    const observed = Array.from({ length: item.frameCount }, (_, frameIndex) => {
      const counts = POSE_COLOURS.map(() => 0);
      const start = frameIndex * pageBytes;
      for (let offset = start; offset < start + pageBytes; offset += info.channels) {
        if (data[offset + 3] < 128) continue;
        POSE_COLOURS.forEach((colour, colourIndex) => {
          const distance = (data[offset] - colour.r) ** 2
            + (data[offset + 1] - colour.g) ** 2
            + (data[offset + 2] - colour.b) ** 2;
          if (distance < 2_500) counts[colourIndex] += 1;
        });
      }
      return counts.indexOf(Math.max(...counts));
    });

    expect(observed).toEqual(buildPoseSequence(item.frameCount));
    expect(new Set(observed)).toEqual(new Set([0, 1, 2, 3]));
  });

  it('masterPaths 与 masters 输入产生相同结果', async () => {
    const item = makeItem({ motionPreset: 'laugh', direction: 'pet-or-person' });
    const masters = await createPoses();
    const directory = await mkdtemp(join(tmpdir(), 'prototype-renderer-'));
    const masterPaths = masters.map((_, index) => join(
      directory,
      `pose-${String(index + 1).padStart(2, '0')}.png`,
    ));
    try {
      await Promise.all(masterPaths.map((path, index) => writeFile(path, masters[index])));
      const fromBuffer = await renderPrototypeGif({ masters, item });
      const fromPath = await renderPrototypeGif({ masterPaths, item });
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
      masters: await createPoses(),
      item: makeItem(overrides),
    })).rejects.toThrow(new RegExp(`${field}.*thanks-render-01`));
  });

  it('必须提供与 poseFiles 对齐的四个 masters 或 masterPaths，且二者互斥', async () => {
    const masters = await createPoses();
    const item = makeItem();

    await expect(renderPrototypeGif({ masters: masters.slice(0, 3), item }))
      .rejects.toThrow(/masters.*恰好 4.*thanks-render-01/);
    await expect(renderPrototypeGif({
      masters,
      masterPaths: item.poseFiles,
      item,
    } as unknown as RenderPrototypeGifOptions)).rejects.toThrow(/二选一.*thanks-render-01/);
    await expect(renderPrototypeGif({
      masters,
      item: makeItem({ poseFiles: item.poseFiles.slice(0, 3) }),
    })).rejects.toThrow(/poseFiles.*恰好 4.*thanks-render-01/);
  });

  it('逐帧处理失败时报告样板 ID 和帧序号', async () => {
    await expect(renderPrototypeGif({
      masters: await createPoses(),
      item: makeItem({ text: '' }),
    })).rejects.toThrow(/渲染样板 thanks-render-01 第 \d+ 帧失败/);
  });

  it('不可读或非 PNG 姿势抛出包含样板 ID 和姿势序号的可定位错误', async () => {
    const poses = await createPoses();
    await expect(renderPrototypeGif({
      masters: [poses[0], Buffer.from('not an image'), poses[2], poses[3]],
      item: makeItem(),
    })).rejects.toThrow(/无法读取姿势.*thanks-render-01.*姿势 2/);

    const jpeg = await sharp({
      create: { width: 20, height: 20, channels: 3, background: '#fff' },
    }).jpeg().toBuffer();
    await expect(renderPrototypeGif({
      masters: [jpeg, poses[1], poses[2], poses[3]],
      item: makeItem(),
    })).rejects.toThrow(/PNG.*thanks-render-01.*姿势 1/);
  });
});
