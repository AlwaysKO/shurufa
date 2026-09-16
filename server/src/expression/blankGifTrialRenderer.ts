import sharp from 'sharp';
import { sceneRich12GridBounds, sceneRich12Timeline } from './sceneRich12Renderer.js';

/** 只用于隔离无字样片，绝不登记到正式素材目录。 */
export async function renderBlankGifTrial(master: Buffer): Promise<{
  gif: Buffer; thumbnail: Buffer; poses: Buffer[]; status: 'trial-only'; publicationAllowed: false;
}> {
  const metadata = await sharp(master).metadata();
  if (metadata.format !== 'png' || !metadata.width || !metadata.height) throw new Error('母版必须为4列3行PNG');
  // 兼容已验收参考母版1447×1087的舍入偏差，拒绝真正非4×3网格。
  const gridWidth = Math.round(metadata.width / 4) * 4;
  const gridHeight = gridWidth / 4 * 3;
  if (Math.abs(gridWidth - metadata.width) > 2 || Math.abs(gridHeight - metadata.height) > 2) {
    throw new Error('母版必须为4列3行PNG');
  }
  const bounds = sceneRich12GridBounds(gridWidth, gridHeight);
  const grid = gridWidth === metadata.width && gridHeight === metadata.height ? master
    : await sharp(master).resize(gridWidth, gridHeight).png().toBuffer();
  const clear = { r: 0, g: 0, b: 0, alpha: 0 };
  const poses = await Promise.all(bounds.map(async bound => sharp({
    create: { width: 240, height: 240, channels: 4, background: clear },
  }).composite([{ input: await sharp(grid).extract(bound)
    .flatten({ background: '#fffaf0' }).resize(236, 236).png().toBuffer(), left: 2, top: 2 }]).png().toBuffer()));
  const timeline = sceneRich12Timeline();
  const gif = await sharp({ create: { width: 240, height: 240 * timeline.length, pageHeight: 240,
    channels: 4, background: clear } })
    .composite(timeline.map(({ pose }, index) => ({ input: poses[pose], left: 0, top: index * 240 })))
    .gif({ loop: 0, delay: timeline.map(frame => frame.delay), colours: 128, dither: 0.7,
      effort: 10, keepDuplicateFrames: true }).toBuffer();
  const thumbnail = await sharp(gif, { page: 0, pages: 1 }).webp({ lossless: true }).toBuffer();
  return { gif, thumbnail, poses, status: 'trial-only', publicationAllowed: false };
}
