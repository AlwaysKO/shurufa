/** 2026-09-16 用户批准现有15张合格无字GIF；仅登记素材，不发布API。 */
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { auditPrototypeGif } from '../src/expression/prototypeAudit.js';
type SourceTemplate = { id: string; source: string; animation: { sha256: string; sourceType: string; provenance: { manifest: string; itemId: string; approvalRecord: string } } };

const root = resolve(import.meta.dirname, '../..');
const sourceRoot = resolve(root, 'assets/expression');
const approvalRecord = 'approvals/2026-09-16-blank-gif-all-15-release.json';
const approval = JSON.parse(await readFile(resolve(sourceRoot, approvalRecord), 'utf8'));
const manifestPath = resolve(sourceRoot, 'manifest.source.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const additions: SourceTemplate[] = approval.additionalTemplates;
const additionalIds = new Set(additions.map(t => t.id));
const templates: SourceTemplate[] = [...manifest.templates.filter((t: SourceTemplate) => !additionalIds.has(t.id)), ...additions];
if (approval.status !== 'approved' || templates.length !== 15 || new Set(templates.map(t => t.animation?.sha256)).size !== 15) throw new Error('批准范围或去重检查失败');
const prepared = [];
for (const template of templates) {
  const animation = template.animation!;
  const bytes = await readFile(additionalIds.has(template.id)
    ? resolve(root, animation.provenance.manifest, '..', 'output', `${animation.provenance.itemId}.gif`)
    : resolve(sourceRoot, template.source));
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  if (!approval.approvedIds.includes(template.id) || !approval.approvedAssets.some((a: {id: string; sourceType: string; sha256: string}) => a.id === template.id && a.sha256 === sha256 && a.sourceType === animation.sourceType)
    || sha256 !== animation.sha256 || (animation.sourceType === 'licensed' && !approval.licensedIds.includes(template.id))) throw new Error(`批准版本不匹配 ${template.id}`);
  const audit = await auditPrototypeGif(bytes, { id: template.id, frameCount: 20, durationMs: 4000 });
  if (audit.issues.length) throw new Error(`质量检查失败 ${template.id}`);
  prepared.push({ template, bytes, audit });
}
for (const { template, bytes } of prepared) if (additionalIds.has(template.id)) await writeFile(resolve(sourceRoot, template.source), bytes);
manifest.templates = templates;
manifest.builtInTemplateIds = templates.map(t => t.id);
manifest.expectedCounts.templates = 15;
manifest.expectedCounts.animatedTemplates = 15;
manifest.version = '2026.09.16.blank-only-15';
await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
await writeFile(resolve(root, 'server/src/expression/catalogVersion.ts'), `export const EXPRESSION_CATALOG_VERSION = '${manifest.version}';\n`);
await writeFile(resolve(sourceRoot, 'approvals/2026-09-16-blank-gif-all-15-quality.json'), JSON.stringify(prepared.map(({template, audit}) => ({id: template.id, sha256: template.animation!.sha256, audit})), null, 2) + '\n');
console.log(templates.map(t => ({id: t.id, sha256: t.animation!.sha256})));
