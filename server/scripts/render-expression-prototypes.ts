import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

import sharp, { type OverlayOptions } from 'sharp';

import {
  type PrototypeGifAuditResult,
  auditPrototypeGif,
} from '../src/expression/prototypeAudit.js';
import {
  type PrototypeManifestItem,
  validatePrototypeManifest,
} from '../src/expression/prototypeManifest.js';
import {
  preflightPrototypePoses,
  publishDirectoryAtomically,
} from '../src/expression/prototypePublication.js';
import {
  buildPrototypeReport,
  verifyPrototypeReport,
} from '../src/expression/prototypeReport.js';
import {
  PROTOTYPE_FONT_PATH,
  renderPrototypeGif,
} from '../src/expression/prototypeRenderer.js';

const PROJECT_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const PROTOTYPES_ROOT = join(PROJECT_ROOT, 'assets/expression/prototypes');
const OUTPUT_ROOT = join(PROJECT_ROOT, 'artifacts/expression-prototypes');
const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 } as const;

interface RenderedPrototype {
  item: PrototypeManifestItem;
  gif: Buffer;
  firstFrame: Buffer;
  audit: PrototypeGifAuditResult;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

async function makeLabel(
  item: PrototypeManifestItem,
  bytes: number,
  width: number,
  height: number,
  dark: boolean,
): Promise<Buffer> {
  const label = `${item.keyword}  ${item.id}\n${Math.round(bytes / 1024)}KB`;
  return sharp({
    text: {
      text: `<span foreground="${dark ? '#f4f4f5' : '#18181b'}">${escapeHtml(label)}</span>`,
      font: `Droid Sans Fallback ${width >= 240 ? 14 : 11}`,
      fontfile: PROTOTYPE_FONT_PATH,
      width,
      height,
      align: 'centre',
      rgba: true,
    },
  }).png().toBuffer();
}

async function renderContactSheet(
  rendered: RenderedPrototype[],
  options: {
    imageSize: 240 | 120;
    background: string;
    dark: boolean;
    outputFile: string;
  },
): Promise<void> {
  const columns = 4;
  const rows = Math.ceil(rendered.length / columns);
  const horizontalPadding = options.imageSize === 240 ? 20 : 14;
  const topPadding = options.imageSize === 240 ? 14 : 10;
  const labelHeight = options.imageSize === 240 ? 50 : 42;
  const cellWidth = options.imageSize + horizontalPadding * 2;
  const cellHeight = options.imageSize + topPadding + labelHeight;
  const layers: OverlayOptions[] = [];

  for (const [index, result] of rendered.entries()) {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const left = column * cellWidth + horizontalPadding;
    const top = row * cellHeight + topPadding;
    const preview = options.imageSize === 240
      ? result.firstFrame
      : await sharp(result.firstFrame).resize(120, 120).png().toBuffer();
    const label = await makeLabel(
      result.item,
      result.gif.length,
      options.imageSize,
      labelHeight,
      options.dark,
    );
    layers.push(
      { input: preview, left, top },
      { input: label, left, top: top + options.imageSize },
    );
  }

  await sharp({
    create: {
      width: cellWidth * columns,
      height: cellHeight * rows,
      channels: 4,
      background: options.background,
    },
  }).composite(layers).webp({ quality: 92, alphaQuality: 100 }).toFile(options.outputFile);
}

function renderPreviewHtml(rendered: RenderedPrototype[]): string {
  const cards = rendered.map(({ item, gif }) => `
    <article class="card">
      <div class="stage"><img src="./gifs/${encodeURIComponent(item.id)}.gif"
        width="240" height="240" alt="${escapeHtml(item.keyword)} ${escapeHtml(item.id)}"></div>
      <h2>${escapeHtml(item.keyword)}</h2>
      <code>${escapeHtml(item.id)}</code>
      <p>${(gif.length / 1024).toFixed(1)}KB · ${item.frameCount} 帧 · ${item.durationMs}ms</p>
    </article>`).join('');
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>原创动态表情样板</title>
  <style>
    :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
    body { margin: 0; padding: 24px; background: #101114; color: #f4f4f5; }
    h1 { margin: 0 0 20px; }
    main { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 18px; }
    .card { padding: 16px; border: 1px solid #34363d; border-radius: 16px; background: #1c1e23; }
    .stage { width: 240px; height: 240px; margin: auto; border-radius: 12px;
      background-color: #fff; background-image: linear-gradient(45deg,#ddd 25%,transparent 25%),
      linear-gradient(-45deg,#ddd 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#ddd 75%),
      linear-gradient(-45deg,transparent 75%,#ddd 75%); background-size: 20px 20px;
      background-position: 0 0,0 10px,10px -10px,-10px 0; }
    img { display: block; }
    h2 { display: inline-block; margin: 12px 10px 4px 0; }
    code { color: #a5b4fc; }
    p { margin: 4px 0 0; color: #a1a1aa; }
  </style>
</head>
<body><h1>12 个最终动态 GIF</h1><main>${cards}</main></body>
</html>\n`;
}

async function main(): Promise<void> {
  const manifestPath = join(PROTOTYPES_ROOT, 'manifest.json');
  const manifest = validatePrototypeManifest(JSON.parse(await readFile(manifestPath, 'utf8')));
  const posePathsById = await preflightPrototypePoses(manifest, PROTOTYPES_ROOT);

  await publishDirectoryAtomically(OUTPUT_ROOT, async (temporaryRoot) => {
    const gifsRoot = join(temporaryRoot, 'gifs');
    await mkdir(gifsRoot, { recursive: true });
    const rendered: RenderedPrototype[] = [];
    for (const item of manifest.items) {
      const masterPaths = posePathsById.get(item.id);
      if (masterPaths === undefined) throw new Error(`预检结果缺少样板 ${item.id}`);
      const gif = await renderPrototypeGif({ masterPaths, item });
      await writeFile(join(gifsRoot, `${item.id}.gif`), gif);
      const audit = await auditPrototypeGif(gif, item);
      const firstFrame = await sharp(gif, { page: 0 }).png().toBuffer();
      rendered.push({ item, gif, firstFrame, audit });
      console.log(`${audit.issues.length === 0 ? '✓' : '✗'} ${item.id} ${(gif.length / 1024).toFixed(1)}KB`);
    }

    const report = buildPrototypeReport(manifest, rendered.map(({ audit }) => audit));
    await writeFile(join(temporaryRoot, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
    await Promise.all([
      renderContactSheet(rendered, {
        imageSize: 240,
        background: '#e4e4e7',
        dark: false,
        outputFile: join(temporaryRoot, 'contact-sheet.webp'),
      }),
      renderContactSheet(rendered, {
        imageSize: 120,
        background: '#fafafa',
        dark: false,
        outputFile: join(temporaryRoot, 'contact-sheet-120-light.webp'),
      }),
      renderContactSheet(rendered, {
        imageSize: 120,
        background: '#111318',
        dark: true,
        outputFile: join(temporaryRoot, 'contact-sheet-120-dark.webp'),
      }),
      writeFile(join(temporaryRoot, 'preview.html'), renderPreviewHtml(rendered)),
    ]);
    const verification = await verifyPrototypeReport({ report, manifest, gifsRoot });
    if (!verification.valid) {
      throw new Error(`报告与 GIF 不一致：${JSON.stringify(verification.issues)}`);
    }
    if (report.fail > 0) {
      throw new Error(`审计失败：${report.fail}/${report.total} 个样板存在问题`);
    }
  });
  console.log(`审计通过：${manifest.items.length}/${manifest.items.length}`);
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
