/** 用户2026-09-16批准的固定六张模板准备；不发布API、不触碰Android。 */
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { renderBlankGifTrial } from '../src/expression/blankGifTrialRenderer.js';
import { auditPrototypeGif } from '../src/expression/prototypeAudit.js';

const root = fileURLToPath(new URL('../../', import.meta.url));
const sourceRoot = join(root, 'assets/expression');
const approvalRecord = 'approvals/2026-09-16-blank-gif-release.json';
const approval = JSON.parse(await readFile(join(sourceRoot, approvalRecord), 'utf8'));
const entries = [
  { id: 'cat-side-eye', keywords: ['嫌弃', '质疑', '侧眼', '调侃'], emotions: ['skeptical'], licensed: false },
  { id: 'man-snicker', keywords: ['偷笑', '憋笑', '调侃', '笑死'], emotions: ['tease'], licensed: false },
  { id: 'line-exasperated', keywords: ['抓狂', '无奈', '无语', '原地裂开'], emotions: ['frustrated'], licensed: false },
  { id: 'otter-smug', keywords: ['得意', '显摆', '骄傲', '给你颁个奖'], emotions: ['proud'], licensed: false },
  { id: 'mushroom-think', keywords: ['思考', '让我想想', '琢磨', '犹豫'], emotions: ['thinking'], licensed: true },
  { id: 'panda-really', keywords: ['质疑', '真的假的', '怀疑', '不信'], emotions: ['skeptical'], licensed: true },
];
const prepared = [];
for (const entry of entries) {
  const id = `blank-${entry.id}`;
  if (approval.status !== 'approved' || !approval.approvedIds.includes(id)
    || (entry.licensed && !approval.licensedIds.includes(id))) throw new Error(`没有正式接入批准：${id}`);
  const bytes = entry.licensed
    ? (await renderBlankGifTrial(await readFile(join(root, 'artifacts/expression-character-trials/reference-01-animated/masters', `${entry.id}.png`)))).gif
    : await readFile(join(root, 'artifacts/expression-character-trials/ai-synthesis-blank-01/output', `${entry.id}.gif`));
  const audit = await auditPrototypeGif(bytes, { id, frameCount: 20, durationMs: 4000 });
  if (audit.issues.length) throw new Error(`${id}审计失败：${JSON.stringify(audit.issues)}`);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  if (!Array.isArray(approval.approvedAssets) || !approval.approvedAssets.some(
    (asset: { id: string; sourceType: string; sha256: string }) => asset.id === id
      && asset.sourceType === (entry.licensed ? 'licensed' : 'ai-original') && asset.sha256 === sha256,
  )) throw new Error(`素材版本与批准记录不一致：${id}`);
  prepared.push({ entry, id, bytes, audit, sha256 });
}
await mkdir(join(sourceRoot, 'templates'), { recursive: true });
const templates = [];
for (const { entry, id, bytes, sha256 } of prepared) {
  const source = `templates/${id}.gif`;
  await writeFile(join(sourceRoot, source), bytes);
  templates.push({ id, type: 'gif', source, keywords: entry.keywords, emotions: entry.emotions,
    textSafeArea: { x: 6, y: entry.id === 'man-snicker' || entry.licensed ? 202 : 190, width: 228,
      height: entry.id === 'man-snicker' || entry.licensed ? 32 : 44 },
    layout: { minFontSize: 12, maxFontSize: 24, textColor: '#222222', strokeColor: '#ffffff', strokeWidth: 1, alignment: 'center', maxLines: 2 },
    animation: { sha256, sourceType: entry.licensed ? 'licensed' : 'ai-original',
      provenance: { manifest: entry.licensed ? 'artifacts/expression-character-trials/reference-01-animated/generation.json'
        : 'artifacts/expression-character-trials/ai-synthesis-blank-01/report.json', itemId: entry.id, approvalRecord } },
  });
}
const manifestPath = join(sourceRoot, 'manifest.source.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const ids = new Set(templates.map(t => t.id));
manifest.templates = [...templates, ...manifest.templates.filter((t: { id: string }) => !ids.has(t.id))];
manifest.builtInTemplateIds = [...new Set([...templates.map(t => t.id), ...manifest.builtInTemplateIds])];
manifest.expectedCounts.templates = manifest.templates.length;
manifest.expectedCounts.animatedTemplates = manifest.templates.filter((t: { type: string }) => t.type === 'gif').length;
manifest.version = '2026.09.16.blank-only-6';
await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
await writeFile(join(root, 'server/src/expression/catalogVersion.ts'), `export const EXPRESSION_CATALOG_VERSION = '${manifest.version}';\n`);
await writeFile(join(sourceRoot, 'approvals/2026-09-16-blank-gif-quality.json'), JSON.stringify(prepared.map(({ entry, id, sha256, audit }) => ({
  id, sourceType: entry.licensed ? 'licensed' : 'ai-original', sha256,
  status: 'approved-for-library', authorizationRecord: approvalRecord, audit,
})), null, 2) + '\n');
console.log(prepared.map(({ id, bytes }) => ({ id, bytes: bytes.length })));
