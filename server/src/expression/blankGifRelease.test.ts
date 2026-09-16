import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import sharp from 'sharp';
import { describe, expect, it } from 'vitest';

describe('2026-09-16 六张无字GIF正式源登记', () => {
  it('六张在模板池并保留原动作和来源，未把第二批试稿混入', async () => {
    const root = new URL('../../../assets/expression/', import.meta.url);
    const manifest = JSON.parse(await readFile(new URL('manifest.source.json', root), 'utf8'));
    const approval = JSON.parse(await readFile(new URL('approvals/2026-09-16-blank-gif-release.json', root), 'utf8'));
    const native = manifest.templates.filter((t: { animation?: unknown }) => t.animation);
    expect(native.map((t: { id: string }) => t.id).sort()).toEqual([...approval.approvedIds].sort());
    expect(native).toHaveLength(6);
    for (const template of native) {
      expect(manifest.builtInTemplateIds).toContain(template.id);
      expect(approval.approvedAssets).toContainEqual({
        id: template.id, sourceType: template.animation.sourceType, sha256: template.animation.sha256,
      });
      const bytes = await readFile(new URL(template.source, root));
      expect(createHash('sha256').update(bytes).digest('hex')).toBe(template.animation.sha256);
      const metadata = await sharp(bytes, { animated: true }).metadata();
      expect(metadata.pages).toBe(20);
      expect(metadata.width).toBe(240);
      expect(metadata.delay?.reduce((a, b) => a + b, 0)).toBe(4000);
      expect(template.animation.sourceType).toBe(approval.licensedIds.includes(template.id) ? 'licensed' : 'ai-original');
      if (!approval.licensedIds.includes(template.id)) {
        const original = await readFile(new URL(`../../../artifacts/expression-character-trials/ai-synthesis-blank-01/output/${template.id.slice(6)}.gif`, import.meta.url));
        expect(bytes).toEqual(original);
      }
    }
    expect(manifest.expectedCounts.templates).toBe(66);
    expect(manifest.expectedCounts.animatedTemplates).toBe(26);
  });
});
