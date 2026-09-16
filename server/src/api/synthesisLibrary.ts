import { Router } from 'express';
import type pg from 'pg';
import sharp from 'sharp';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { publicExpressionAsset, synthesisRowAsset, systemExpressionCatalog } from './expressionSnapshot.js';
function directory() { return join(process.cwd(), 'uploads', 'synthesis'); }
function personal(row: any) { return { ...publicExpressionAsset(synthesisRowAsset(row)), name: row.name, source: 'personal', deletable: true, sourceStatement: row.source_statement }; }
function system(asset: any) { return { ...publicExpressionAsset(asset), name: asset.id, source: 'system', deletable: false }; }
function validateFields(b: any) {
    const a = b.textSafeArea, l = b.layout;
    if (b.noTextConfirmed !== true || b.rightsConfirmed !== true || typeof b.sourceStatement !== 'string' || !b.sourceStatement.trim() || b.sourceStatement.length > 2000)
        throw Error('请确认无字及使用权，并填写来源授权声明');
    if (!a || ![a.x, a.y, a.width, a.height].every(Number.isInteger) || a.x < 0 || a.y < 0 || a.width <= 0 || a.height <= 0 || a.x + a.width > 240 || a.y + a.height > 240)
        throw Error('文字安全区必须位于240×240内');
    if (!l || ![l.minFontSize, l.maxFontSize, l.maxLines, l.strokeWidth].every(Number.isInteger) || l.minFontSize < 8 || l.maxFontSize < l.minFontSize || l.maxFontSize > 120 || l.maxLines < 1 || l.maxLines > 6 || l.strokeWidth < 0 || l.strokeWidth > 10 || !['start', 'center', 'end'].includes(l.alignment) || ![l.textColor, l.strokeColor].every(c => /^#[a-f0-9]{6}$/i.test(c)))
        throw Error('文字布局不合法');
    const minimumGlyphSize = l.minFontSize + 2 * l.strokeWidth;
    if (a.width < minimumGlyphSize || a.height < minimumGlyphSize)
        throw Error(`文字安全区宽高必须至少 ${minimumGlyphSize}px，至少容纳一个最小字及描边`);
}
export function createSynthesisLibraryRouter(pool: pg.Pool) {
    const router = Router();
    router.get('/synthesis-library', async (_req, res, next) => { try {
        const [catalog, rows] = await Promise.all([systemExpressionCatalog(), pool.query('SELECT * FROM synthesis_asset WHERE user_id = $1 ORDER BY created_at DESC', [res.locals.userId])]);
        const systemAssets = catalog.templates.filter(a => a.type === 'synthesis-template' && a.format === 'gif' && !a.embeddedText);
        const hashes = new Set(systemAssets.map(a => a.sha256));
        const assets = [...systemAssets.map(system), ...rows.rows.filter(row => !hashes.has(row.sha256)).map(personal)];
        res.json({ assets, total: assets.length });
    }
    catch (e) {
        next(e);
    } });
    router.post('/synthesis-library', async (req, res, next) => {
        let bytes: Buffer;
        try {
            validateFields(req.body ?? {});
            if (typeof req.body.file_base64 !== 'string' || req.body.file_base64.length > 350000)
                throw Error('GIF最大250KiB');
            bytes = Buffer.from(req.body.file_base64, 'base64');
            if (!bytes.length || bytes.length > 250 * 1024)
                throw Error('GIF最大250KiB');
            const meta = await sharp(bytes, { animated: true, limitInputPixels: 240 * 240 * 100 }).metadata();
            if (meta.format !== 'gif' || meta.width !== 240 || (meta.pageHeight ?? meta.height) !== 240 || (meta.pages ?? 1) < 2 || (meta.pages ?? 0) > 100)
                throw Error('仅接受240×240多帧GIF（2～100帧）');
            await sharp(bytes, { animated: true, limitInputPixels: 240 * 240 * 100 }).raw().toBuffer();
        }
        catch (e) {
            res.status(400).json({ error: (e as Error).message });
            return;
        }
        try {
            const sha = createHash('sha256').update(bytes).digest('hex');
            const catalog = await systemExpressionCatalog();
            const existing = catalog.templates.find(a => a.type === 'synthesis-template' && a.sha256 === sha);
            if (existing) {
                res.json({ asset: system(existing), duplicate: true });
                return;
            }
            const id = randomUUID(), fileName = `${id}.gif`;
            await mkdir(directory(), { recursive: true });
            await writeFile(join(directory(), fileName), bytes, { flag: 'wx' });
            let result;
            try {
                result = await pool.query(`INSERT INTO synthesis_asset(id,user_id,name,file_name,sha256,width,height,text_safe_area,layout,source_statement,no_text_confirmed,rights_confirmed) VALUES($1,$2,$3,$4,$5,240,240,$6,$7,$8,true,true) ON CONFLICT(user_id,sha256) DO NOTHING RETURNING *`, [id, res.locals.userId, String(req.body.name ?? req.body.filename ?? '无字GIF').slice(0, 100), fileName, sha, req.body.textSafeArea, req.body.layout, req.body.sourceStatement.trim()]);
            }
            catch (e) {
                await unlink(join(directory(), fileName));
                throw e;
            }
            if (!result.rows.length || result.rows[0].id !== id) {
                await unlink(join(directory(), fileName));
                const duplicate = await pool.query('SELECT * FROM synthesis_asset WHERE user_id = $1 AND sha256 = $2', [res.locals.userId, sha]);
                res.json({ asset: personal(duplicate.rows[0]), duplicate: true });
                return;
            }
            res.status(201).json({ asset: personal(result.rows[0]), duplicate: false });
        }
        catch (e) {
            next(e);
        }
    });
    router.delete('/synthesis-library/:id', async (req, res, next) => { try {
        const id = req.params.id.replace(/^synthesis-/, '');
        if (!/^[a-f0-9-]{36}$/i.test(id)) {
            res.status(400).json({ error: 'invalid id' });
            return;
        }
        const owned = await pool.query('SELECT file_name FROM synthesis_asset WHERE id = $1 AND user_id = $2', [id, res.locals.userId]);
        if (!owned.rows.length) {
            res.status(404).json({ error: 'not found' });
            return;
        }
        const result = await pool.query('DELETE FROM synthesis_asset WHERE id = $1 AND user_id = $2 RETURNING file_name', [id, res.locals.userId]);
        if (!result.rows.length) {
            res.status(404).json({ error: 'not found' });
            return;
        }
        await unlink(join(directory(), result.rows[0].file_name)).catch(e => { if (e.code !== 'ENOENT')
            throw e; });
        res.json({ ok: true });
    }
    catch (e) {
        next(e);
    } });
    return router;
}
