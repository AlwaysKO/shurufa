import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import sharp from 'sharp';
import { describe, expect, it } from 'vitest';

describe('2026-09-16 十五张无字GIF正式源登记', () => {
  it('十五张在模板池保留原动作来源，排除不合格摆烂稿', async () => {
    const root = new URL('../../../assets/expression/', import.meta.url);
    const manifest = JSON.parse(await readFile(new URL('manifest.source.json', root), 'utf8'));
    const approval = JSON.parse(await readFile(new URL('approvals/2026-09-16-blank-gif-all-15-release.json', root), 'utf8'));
    const native = manifest.templates.filter((t: { animation?: unknown }) => t.animation);
    expect(native.map((t: { id: string }) => t.id).sort()).toEqual([...approval.approvedIds].sort());
    expect(native).toHaveLength(15);
    for (const template of native) {
      expect(manifest.builtInTemplateIds).toContain(template.id);
      expect(approval.approvedAssets).toContainEqual({
        id: template.id, sourceType: template.animation.sourceType, sha256: template.animation.sha256,
      });
      const bytes = await readFile(new URL(template.source, root));
      if (approval.additionalTemplates.some((t: { id: string }) => t.id === template.id)) {
        const provenance = template.animation.provenance;
        const original = await readFile(new URL(`../../../${provenance.manifest.replace(/report\.json$/, '')}output/${provenance.itemId}.gif`, import.meta.url));
        expect(bytes).toEqual(original);
      }
      if (template.id.includes('mushroom-') && template.animation.provenance.manifest.includes('fullbody')) {
        expect(template.textSafeArea.y).toBeGreaterThanOrEqual(220);
        expect(template.layout.maxLines).toBe(1);
        expect(template.layout.maxFontSize).toBeLessThanOrEqual(12);
      }
      expect(createHash('sha256').update(bytes).digest('hex')).toBe(template.animation.sha256);
      const metadata = await sharp(bytes, { animated: true }).metadata();
      expect(metadata.pages).toBe(20);
      expect(metadata.width).toBe(240);
      expect(metadata.delay?.reduce((a, b) => a + b, 0)).toBe(4000);
      expect(template.animation.sourceType).toBe(approval.licensedIds.includes(template.id) ? 'licensed' : 'ai-original');
      if (['blank-cat-side-eye', 'blank-man-snicker', 'blank-line-exasperated', 'blank-otter-smug'].includes(template.id)) {
        const original = await readFile(new URL(`../../../artifacts/expression-character-trials/ai-synthesis-blank-01/output/${template.id.slice(6)}.gif`, import.meta.url));
        expect(bytes).toEqual(original);
      }
    }
    expect(manifest.expectedCounts.templates).toBe(15);
    expect(manifest.templates).toHaveLength(15);
    expect(manifest.prebuiltSourceTemplates).toHaveLength(60);
    expect(native.some((t: { id: string }) => t.id.includes('bean-slacking'))).toBe(false);
    expect(new Set(native.map((t: { animation: { sha256: string } }) => t.animation.sha256)).size).toBe(15);
    expect(manifest.expectedCounts.animatedTemplates).toBe(15);
  });
});
