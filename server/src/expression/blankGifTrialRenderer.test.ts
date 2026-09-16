import { describe, expect, it } from 'vitest';
import sharp from 'sharp';
import { renderBlankGifTrial } from './blankGifTrialRenderer.js';

describe('无字合成隔离样片编码', () => {
  it('保留十二姿势与四秒节奏且没有叠字步骤', async () => {
    const poses = await Promise.all(Array.from({ length: 12 }, (_, i) => sharp({
      create: { width: 240, height: 240, channels: 4, background: { r: 20 * i, g: 80, b: 180, alpha: 1 } },
    }).png().toBuffer()));
    const master = await sharp({ create: { width: 960, height: 720, channels: 4, background: 'white' } })
      .composite(poses.map((input, i) => ({ input, left: (i % 4) * 240, top: Math.floor(i / 4) * 240 }))).png().toBuffer();
    const result = await renderBlankGifTrial(master);
    const metadata = await sharp(result.gif, { animated: true }).metadata();
    expect(metadata.width).toBe(240);
    expect(metadata.pageHeight).toBe(240);
    expect(metadata.pages).toBe(20);
    expect(metadata.delay?.reduce((a, b) => a + b, 0)).toBe(4000);
    expect(metadata.loop).toBe(0);
    const corner = await sharp(result.gif, { page: 0, pages: 1 }).ensureAlpha().extract({ left: 0, top: 0, width: 1, height: 1 }).raw().toBuffer();
    expect(corner[3]).toBe(0);
    expect(result.poses).toHaveLength(12);
    expect(result.gif.length).toBeLessThan(250_000);
    expect(result.status).toBe('trial-only');
    expect(result.publicationAllowed).toBe(false);
  });
  it('接受参考母版两像素以内的4比3舍入偏差', async () => {
    const master = await sharp({ create: { width: 1447, height: 1087, channels: 4, background: 'white' } }).png().toBuffer();
    const result = await renderBlankGifTrial(master);
    expect((await sharp(result.gif, { animated: true }).metadata()).pages).toBe(20);
  });
  it('拒绝不是十二格的母版', async () => {
    const master = await sharp({ create: { width: 960, height: 960, channels: 4, background: 'white' } }).png().toBuffer();
    await expect(renderBlankGifTrial(master)).rejects.toThrow('4列3行');
  });
});
