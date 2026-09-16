import { readKeywordGifCatalog, mergeKeywordGifCatalog, removedKeywordGifHashes } from '../expression/keywordGifLibrary.js';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type pg from 'pg';
import type { GeneratedExpressionCatalog } from '../expression/assetGenerator.js';
import type { ExpressionAsset } from '../types/expression.js';
export async function systemExpressionCatalog(): Promise<GeneratedExpressionCatalog> {
    const catalog = JSON.parse(await readFile(join(process.cwd(), '.runtime/expression-assets/catalog.json'), 'utf8'));
    return { ...catalog, templates: mergeKeywordGifCatalog(catalog.templates, await readKeywordGifCatalog()) };
}
export function publicExpressionAsset(asset: ExpressionAsset) {
    const uploaded = asset.sourceType === 'owner-upload';
    return { ...asset, version: asset.sha256, url: uploaded ? `/uploads/${asset.fileName}` : `/uploads/expression/${asset.fileName}`, thumbnail_url: asset.thumbnailFileName ? `/uploads/expression/${asset.thumbnailFileName}` : null };
}
export function synthesisRowAsset(row: any): ExpressionAsset {
    return { id: `synthesis-${row.id}`, type: 'synthesis-template', format: 'gif', sourceType: 'owner-upload', distribution: 'remote', version: row.sha256, sha256: row.sha256, fileName: `synthesis/${row.file_name}`, thumbnailFileName: null, width: row.width, height: row.height, keywords: [], emotions: [], embeddedText: null, textSafeArea: row.text_safe_area, layout: row.layout, heat: 0 };
}
export async function expressionSnapshot(pool: pg.Pool, userId: string) {
    const [system, stickers, synthesis] = await Promise.all([systemExpressionCatalog(), pool.query('SELECT id, keywords, file_name, format, width, height, sha256 FROM sticker WHERE user_id = $1 ORDER BY id', [userId]), pool.query('SELECT * FROM synthesis_asset WHERE user_id = $1 ORDER BY id', [userId])]);
    const personal: ExpressionAsset[] = stickers.rows.filter(row => /^[a-f0-9]{64}$/.test(row.sha256 ?? '')).map(row => ({ id: `sticker-${row.id}`, type: 'prebuilt', format: row.format, sourceType: 'owner-upload', distribution: 'remote', version: row.sha256, sha256: row.sha256, fileName: `stickers/${row.file_name}`, thumbnailFileName: null, width: row.width ?? 240, height: row.height ?? 240, keywords: [...new Set<string>(String(row.keywords).split(/[,，]/).map(s => s.trim()).filter(Boolean))], emotions: [], embeddedText: null, textSafeArea: null, layout: null, heat: 0 }));
    const synthesisHashes = new Set(system.templates.filter(asset => asset.type === 'synthesis-template').map(asset => asset.sha256));
    const uploadedSynthesis = synthesis.rows.map(synthesisRowAsset).filter(asset => !synthesisHashes.has(asset.sha256));
    const removed = await removedKeywordGifHashes(pool, userId);
    const templates = [...system.templates.filter(asset => asset.type !== 'prebuilt' || !removed.has(asset.sha256)), ...personal, ...uploadedSynthesis];
    // Hash only catalog/DB metadata, never a GIF. Usage counters deliberately excluded.
    const version = createHash('sha256').update(JSON.stringify({ userId, removed: [...removed], system, stickers: stickers.rows, synthesis: synthesis.rows.map(({ created_at, ...row }) => row) })).digest('hex');
    return { ...system, version, complete: true, templates, retiredTemplateIds: system.retiredTemplateIds ?? [] };
}
