/** 第二批四张，仅隔离试稿；不登记进正式模板库。 */
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { renderBlankGifTrial } from '../src/expression/blankGifTrialRenderer.js';
import { auditPrototypeGif } from '../src/expression/prototypeAudit.js';
const root = fileURLToPath(new URL('../../artifacts/expression-character-trials/ai-synthesis-blank-02/', import.meta.url));
const items = [
  { id: 'dog-shocked', emotion: '震惊', style: '写实动物' },
  { id: 'bird-hurt', emotion: '委屈', style: '绒毛小鸟' },
  { id: 'bean-slacking', emotion: '摆烂', style: '极简线条' },
  { id: 'hamster-pleading', emotion: '求饶', style: '立体小动物' },
];
const report: Array<{ id: string; audit: Awaited<ReturnType<typeof auditPrototypeGif>>; [key: string]: unknown }> = [];
await mkdir(join(root, 'output'), { recursive: true });
for (const item of items) {
  const master = await readFile(join(root, 'masters', `${item.id}.png`));
  const result = await renderBlankGifTrial(master);
  const audit = await auditPrototypeGif(result.gif, { id: item.id, frameCount: 20, durationMs: 4000 });
  await writeFile(join(root, 'output', `${item.id}.gif`), result.gif);
  await writeFile(join(root, 'output', `${item.id}.webp`), result.thumbnail);
  await mkdir(join(root, 'poses', item.id), { recursive: true });
  for (const [index, pose] of result.poses.entries()) await writeFile(join(root, 'poses', item.id, `${index + 1}.png`), pose);
  report.push({ ...item, status: 'trial-only', publicationAllowed: false, sourceType: 'ai-original',
    generatorTool: 'built-in-image_gen', embeddedText: null, promptFile: `prompts/${item.id}.md`,
    masterSha256: createHash('sha256').update(master).digest('hex'),
    gifSha256: createHash('sha256').update(result.gif).digest('hex'), audit });
}
await writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n');
await writeFile(join(root, 'preview.html'), `<!doctype html><meta charset="utf-8"><title>第二批无字GIF样片</title>
<style>body{font:16px system-ui;background:#f4f1ec;padding:24px}main{display:flex;flex-wrap:wrap;gap:24px}article{padding:16px;background:white;border-radius:16px}img{width:240px;height:240px}</style>
<h1>第二批 · 无字动态样片</h1><p>仅供动态审核，未接入正式图库。请检查情绪、动作和循环。</p><main>${items.map(item => `<article><img src="output/${item.id}.gif"><h2>${item.emotion}</h2><p>${item.style} · 4秒</p><p>${report.find(row => row.id === item.id)!.audit.issues.length ? '质量未通过：循环衔接待修，不可入库' : '机器检查通过，待用户动态审核'}</p></article>`).join('')}</main>`);
console.log(report.map(({ id, audit }) => ({ id, bytes: audit.metadata.bytes, issues: audit.issues })));
if (report.some(row => row.audit.issues.length > 0)) process.exitCode = 1;
