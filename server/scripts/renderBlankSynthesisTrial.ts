import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import sharp from 'sharp';
import { renderBlankGifTrial } from '../src/expression/blankGifTrialRenderer.js';
import { auditPrototypeGif } from '../src/expression/prototypeAudit.js';

const root = fileURLToPath(new URL('../../artifacts/expression-character-trials/ai-synthesis-blank-01/', import.meta.url));
const items = [
  { id: 'cat-side-eye', emotion: '嫌弃质疑', style: '写实动物' },
  { id: 'man-snicker', emotion: '偷笑调侃', style: '原创中国成年男性', personOrigin: 'China', personGender: 'male', adult: true },
  { id: 'line-exasperated', emotion: '抓狂无奈', style: '极简双角色互动' },
  { id: 'otter-smug', emotion: '得意显摆', style: '原创立体动物' },
];
const sha = (value: Buffer) => createHash('sha256').update(value).digest('hex');
const report = [];
await mkdir(join(root, 'output'), { recursive: true });
for (const item of items) {
  const master = await readFile(join(root, 'masters', `${item.id}.png`));
  const result = await renderBlankGifTrial(master);
  await writeFile(join(root, 'output', `${item.id}.gif`), result.gif);
  await writeFile(join(root, 'output', `${item.id}.webp`), result.thumbnail);
  await mkdir(join(root, 'poses', item.id), { recursive: true });
  for (const [index, pose] of result.poses.entries()) {
    await writeFile(join(root, 'poses', item.id, `${index + 1}.png`), pose);
  }
  const audit = await auditPrototypeGif(result.gif, { id: item.id, frameCount: 20, durationMs: 4000 });
  const metadata = await sharp(result.gif, { animated: true }).metadata();
  report.push({ ...item, sourceType: 'ai-original', status: result.status, publicationAllowed: false,
    generatorTool: 'built-in-image_gen', embeddedText: null, masterSha256: sha(master), gifSha256: sha(result.gif), bytes: result.gif.length,
    frames: metadata.pages, durationMs: metadata.delay?.reduce((a, b) => a + b, 0),
    promptFile: `prompts/${item.id}.md`,
    revisionPromptFile: ['line-exasperated', 'otter-smug'].includes(item.id) ? `prompts/${item.id}-fix.md` : null, audit });
}
await writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n');
await writeFile(join(root, 'preview.html'), `<!doctype html><meta charset="utf-8"><title>AI合成无字GIF · 隔离样片</title>
<style>body{font:16px system-ui;background:#f4f1ec;padding:24px;color:#282438}main{display:flex;flex-wrap:wrap;gap:24px}article{background:white;padding:16px;border-radius:16px}img{width:240px;height:240px}small{display:block;max-width:240px}</style>
<h1>4 张无字动态样片</h1><p>仅供动态审核，未接入正式图库。请选择满意方向并指出节奏/表演问题。</p><main>${items.map(item => `<article><img src="output/${item.id}.gif"><h2>${item.emotion}</h2><small>${item.style} · 240×240 · 4秒</small></article>`).join('')}</main>`);
console.log(report.map(({ id, bytes, frames, durationMs, audit }) => ({ id, bytes, frames, durationMs, issues: audit.issues })));
