import { loadSynthesisOrder, orderSynthesisAssets } from './synthesisOrder.js';
import { loadStickerLibrary } from './stickerLibrary.js';
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
    return { id: row.system_asset_id ?? `synthesis-${row.id}`, type: 'synthesis-template', format: row.file_name.split('.').pop(), sourceType: 'owner-upload', distribution: 'remote', version: row.sha256, sha256: row.sha256, fileName: `synthesis/${row.file_name}`, thumbnailFileName: null, width: row.width, height: row.height, keywords: [], emotions: [], embeddedText: null, textSafeArea: row.text_safe_area, layout: row.layout, heat: 0 };
}
// 后台和手机共用可见列表；保留历史上传，系统同图只显示一次。
export function mergedSynthesisAssets(templates: ExpressionAsset[], rows: any[], order: string[]) {
    const uploaded = rows.map(synthesisRowAsset);
    const byId = new Map(uploaded.map(asset => [asset.id, asset]));
    const hashes = new Set(uploaded.map(asset => asset.sha256));
    const system = templates.filter(asset => asset.type === 'synthesis-template')
        .flatMap(asset => byId.has(asset.id) ? [byId.get(asset.id)!] : hashes.has(asset.sha256) ? [] : [asset]);
    const systemIds = new Set(system.map(asset => asset.id));
    return orderSynthesisAssets(uploaded.filter(asset => !systemIds.has(asset.id)), system, order);
}
export async function expressionSnapshot(pool: pg.Pool, userId: string) {
    const [system, stickers, synthesis, savedOrder] = await Promise.all([systemExpressionCatalog(), pool.query('SELECT id, keywords, file_name, format, width, height, sha256 FROM sticker ORDER BY id'), pool.query('SELECT * FROM synthesis_asset ORDER BY created_at DESC, id DESC'), loadSynthesisOrder(pool)]);
    const personal: ExpressionAsset[] = stickers.rows.filter(row => /^[a-f0-9]{64}$/.test(row.sha256 ?? '')).map(row => ({ id: `sticker-${row.id}`, type: 'prebuilt', format: row.format, sourceType: 'owner-upload', distribution: 'remote', version: row.sha256, sha256: row.sha256, fileName: `stickers/${row.file_name}`, thumbnailFileName: null, width: row.width ?? 240, height: row.height ?? 240, keywords: [...new Set<string>(String(row.keywords).split(/[,，]/).map(s => s.trim()).filter(Boolean))], emotions: [], embeddedText: null, textSafeArea: null, layout: null, heat: 0 }));
    const removed = await removedKeywordGifHashes(pool, userId);
    const library = await loadStickerLibrary(pool, userId);
    const visibleIds = new Set(library.groups.flatMap(group => group.assets.map(asset => asset.source === 'personal' ? `sticker-${asset.id}` : String(asset.id))));
    const orderedSynthesis = mergedSynthesisAssets(system.templates, synthesis.rows, savedOrder);
    const synthesisOrder = orderedSynthesis.map(asset => asset.id);
    const templates = [...system.templates.filter(asset => asset.type !== 'synthesis-template' && (asset.type !== 'prebuilt' || (!removed.has(asset.sha256) && visibleIds.has(asset.id)))),
      ...personal.filter(asset => visibleIds.has(asset.id)), ...orderedSynthesis];
    const availableIds = new Set(templates.filter(asset => asset.type === 'prebuilt').map(asset => asset.id));
    const recommendationGroups = library.groups.map(group => ({ keyword: group.keyword, aliases: group.aliases,
        assetIds: group.assets.map(asset => asset.source === 'personal' ? `sticker-${asset.id}` : String(asset.id)).filter(id => availableIds.has(id)),
    }));
    // Hash only catalog/DB metadata, never a GIF. Usage counters deliberately excluded.
    const version = createHash('sha256').update(JSON.stringify({ userId, recommendationGroups, synthesisOrder, removed: [...removed], system, stickers: stickers.rows, synthesis: synthesis.rows.map(({ created_at, ...row }) => row) })).digest('hex');
    const retired = new Set(system.retiredTemplateIds ?? []);
    const recommendationVersion = createHash('sha256').update(JSON.stringify({
        recommendationGroups,
        assets: templates.filter(asset => asset.type === 'prebuilt' && !retired.has(asset.id)).map(publicExpressionAsset),
    })).digest('hex');
    return { ...system, version, recommendationVersion, complete: true, templates, recommendationGroups, synthesisOrder, retiredTemplateIds: system.retiredTemplateIds ?? [] };
}
