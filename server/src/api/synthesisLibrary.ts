import { loadSynthesisOrder, orderSynthesisAssets } from './synthesisOrder.js';
import { Router, raw } from 'express';
import type pg from 'pg';
import sharp from 'sharp';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { publicExpressionAsset, synthesisRowAsset, systemExpressionCatalog } from './expressionSnapshot.js';
function directory() { return join(process.cwd(), 'uploads', 'synthesis'); }
function personal(row: any) { return { ...publicExpressionAsset(synthesisRowAsset(row)), name: row.name, source: 'personal', deletable: true, sourceStatement: row.source_statement }; }
function system(asset: any) { return { ...publicExpressionAsset(asset), name: asset.id, source: 'system', deletable: false }; }
function validateFields(b: any, width = 240, height = 240) {
    const a = b.textSafeArea, l = b.layout;
    if (b.sourceStatement !== undefined && (typeof b.sourceStatement !== 'string' || b.sourceStatement.length > 2000))
        throw Error('来源说明须为不超过2000字的文本');
    if (!a || ![a.x, a.y, a.width, a.height].every(Number.isInteger) || a.x < 0 || a.y < 0 || a.width <= 0 || a.height <= 0 || a.x + a.width > width || a.y + a.height > height)
        throw Error(`文字安全区必须位于${width}×${height}内`);
    if (!l || ![l.minFontSize, l.maxFontSize, l.maxLines, l.strokeWidth].every(Number.isInteger) || l.minFontSize < 8 || l.maxFontSize < l.minFontSize || l.maxFontSize > 120 || l.maxLines < 1 || l.maxLines > 6 || l.strokeWidth < 0 || l.strokeWidth > 10 || !['start', 'center', 'end'].includes(l.alignment) || ![l.textColor, l.strokeColor].every(c => /^#[a-f0-9]{6}$/i.test(c)))
        throw Error('文字布局不合法');
    const minimumGlyphSize = l.minFontSize + 2 * l.strokeWidth;
    if (a.width < minimumGlyphSize || a.height < minimumGlyphSize)
        throw Error(`文字安全区宽高必须至少 ${minimumGlyphSize}px，至少容纳一个最小字及描边`);
}
async function readImage(input: unknown): Promise<{bytes: Buffer; format: string; width: number; height: number}> {
    if (!Buffer.isBuffer(input) && typeof input !== 'string') throw Error('请选择图片');
    const bytes = Buffer.isBuffer(input) ? input : Buffer.from(input as string, 'base64');
    if (!bytes.length || bytes.length > 10 * 1024 * 1024) throw Error('图片为空或超过上传通道10MiB容量');
    const meta = await sharp(bytes, { animated: true }).metadata();
    if (!['gif', 'png', 'jpeg', 'webp'].includes(meta.format ?? '') || !meta.width || !(meta.pageHeight ?? meta.height))
        throw Error('请选择可读取的 GIF、PNG、JPG 或 WebP 图片');
    // 手机现有动态叠字使用GIF解码器；其他多帧格式转换为GIF，不能只取首帧。
    if (meta.format !== 'gif' && (meta.pages ?? 1) > 1)
        return readImage(await sharp(bytes, { animated: true }).gif({ delay: meta.delay, loop: meta.loop }).toBuffer());
    // 原始文件直接保存，尺寸与帧数不作为素材准入条件。
    return { bytes, format: meta.format!, width: meta.width, height: (meta.pageHeight ?? meta.height)! };
}
function imageFields(body: any, width: number, height: number) {
    const b = { ...body };
    if (b.coordinateWidth && b.coordinateHeight && b.textSafeArea) {
        const sx = width / b.coordinateWidth, sy = height / b.coordinateHeight;
        const a = b.textSafeArea;
        const x = Math.round(a.x * sx), y = Math.round(a.y * sy);
        b.textSafeArea = { x, y, width: Math.min(width - x, Math.max(1, Math.round(a.width * sx))), height: Math.min(height - y, Math.max(1, Math.round(a.height * sy))) };
    }
    return b;
}
export function createSynthesisLibraryRouter(pool: pg.Pool) {
    const router = Router();
    router.use('/synthesis-library', (req, res, next) => {
        if (!['POST', 'PATCH'].includes(req.method)) return next();
        raw({ type: 'application/octet-stream', limit: '10mb' })(req, res, error => {
            if (error) { res.status(error.type === 'entity.too.large' ? 413 : 400).json({ error: '图片上传失败或超过上传通道10MiB容量' }); return; }
            if (Buffer.isBuffer(req.body)) {
                try { const file = req.body; req.body = JSON.parse(Buffer.from(req.get('X-Upload-Metadata') ?? '', 'base64').toString('utf8')); req.body.file_base64 = file; }
                catch { res.status(400).json({ error: '上传参数不合法' }); return; }
            }
            next();
        });
    });
    async function listing(userId: string) {
        const [catalog, rows, order] = await Promise.all([systemExpressionCatalog(),
            pool.query('SELECT * FROM synthesis_asset WHERE user_id = $1 ORDER BY created_at DESC, id DESC', [userId]),
            loadSynthesisOrder(pool, userId)]);
        const systemAssets = catalog.templates.filter(a => a.type === 'synthesis-template' && a.format === 'gif' && !a.embeddedText);
        const hashes = new Set(systemAssets.map(a => a.sha256));
        return orderSynthesisAssets(rows.rows.filter(row => !hashes.has(row.sha256)).map(personal), systemAssets.map(system), order);
    }
    router.get('/synthesis-library', async (_req, res, next) => {
        try { const assets = await listing(res.locals.userId); res.json({ assets, total: assets.length }); }
        catch (e) { next(e); }
    });
    router.patch('/synthesis-library/order', async (req, res, next) => {
        const order = req.body?.assetOrder;
        if (!Array.isArray(order) || order.length > 10000 || !order.every(id => typeof id === 'string' && id.length > 0 && id.length <= 200) || new Set(order).size !== order.length) {
            res.status(400).json({ error: '底图排序必须为不重复的图片ID列表' }); return;
        }
        try {
            const current = await listing(res.locals.userId);
            const ids = new Set(current.map(asset => asset.id));
            if (ids.size !== order.length || !order.every(id => ids.has(id))) {
                res.status(409).json({ error: '底图库已变化或包含不可用图片，请刷新后重新排序' }); return;
            }
            await pool.query(`INSERT INTO synthesis_library_order(user_id,asset_order) VALUES($1,$2)
                ON CONFLICT(user_id) DO UPDATE SET asset_order=EXCLUDED.asset_order`, [res.locals.userId, JSON.stringify(order)]);
            const assets = await listing(res.locals.userId);
            res.json({ assets, total: assets.length });
        } catch (e) { next(e); }
    });
    router.post('/synthesis-library', async (req, res, next) => {
        let image: Awaited<ReturnType<typeof readImage>>;
        try {
            image = await readImage(req.body?.file_base64);
            req.body = imageFields(req.body, image.width, image.height);
            validateFields(req.body, image.width, image.height);
        }
        catch (e) {
            res.status(400).json({ error: (e as Error).message });
            return;
        }
        try {
            const { bytes, width, height, format } = image;
            const sha = createHash('sha256').update(bytes).digest('hex');
            const catalog = await systemExpressionCatalog();
            const existing = catalog.templates.find(a => a.type === 'synthesis-template' && a.sha256 === sha);
            if (existing) {
                res.json({ asset: system(existing), duplicate: true });
                return;
            }
            const id = randomUUID(), fileName = `${id}.${format}`;
            await mkdir(directory(), { recursive: true });
            await writeFile(join(directory(), fileName), bytes, { flag: 'wx' });
            let result;
            try {
                result = await pool.query(`INSERT INTO synthesis_asset(id,user_id,name,file_name,sha256,width,height,text_safe_area,layout,source_statement,no_text_confirmed,rights_confirmed) VALUES($1,$2,$3,$4,$5,$11,$12,$6,$7,$8,$9,$10) ON CONFLICT(user_id,sha256) DO NOTHING RETURNING *`, [id, res.locals.userId, String(req.body.name ?? req.body.filename ?? '无字GIF').slice(0, 100), fileName, sha, req.body.textSafeArea, req.body.layout, (req.body.sourceStatement ?? '').trim(), req.body.noTextConfirmed === true, req.body.rightsConfirmed === true, width, height]);
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
    router.patch('/synthesis-library/:id', async (req, res, next) => {
        const id = req.params.id.replace(/^synthesis-/, '');
        if (!/^[a-f0-9-]{36}$/i.test(id)) { res.status(400).json({ error: 'invalid id' }); return; }
        let stagedFile: string | undefined;
        try {
            const owned = await pool.query('SELECT * FROM synthesis_asset WHERE id = $1 AND user_id = $2', [id, res.locals.userId]);
            const old = owned.rows[0];
            if (!old) { res.status(404).json({ error: 'not found' }); return; }
            let b = req.body ?? {};
            let bytes: Buffer | undefined;
            let image: Awaited<ReturnType<typeof readImage>> | undefined;
            try {
                if (b.file_base64 !== undefined) {
                    image = await readImage(b.file_base64); bytes = image.bytes;
                    b = imageFields(b, image.width, image.height);
                }
                validateFields(b, image?.width ?? old.width, image?.height ?? old.height);
                if (typeof b.name !== 'string' || !b.name.trim() || b.name.trim().length > 100) throw Error('底图名称须为1～100字');
            } catch (e) { res.status(400).json({ error: (e as Error).message }); return; }
            const sha = bytes ? createHash('sha256').update(bytes).digest('hex') : old.sha256;
            if (sha !== old.sha256) {
                const catalog = await systemExpressionCatalog();
                const duplicate = await pool.query('SELECT id FROM synthesis_asset WHERE user_id = $1 AND sha256 = $2', [res.locals.userId, sha]);
                if (duplicate.rows.length || catalog.templates.some(a => a.type === 'synthesis-template' && a.sha256 === sha)) {
                    res.status(409).json({ error: '相同 GIF 已存在，请直接使用已有底图' }); return;
                }
                const fileName = `${randomUUID()}.${image!.format}`;
                await mkdir(directory(), { recursive: true });
                await writeFile(join(directory(), fileName), bytes!, { flag: 'wx' });
                stagedFile = fileName;
            }
            const updated = await pool.query(`UPDATE synthesis_asset SET name=$1, source_statement=$2, text_safe_area=$3, layout=$4, file_name=$5, sha256=$6, width=$10, height=$11
                WHERE id=$7 AND user_id=$8 AND file_name=$9 RETURNING *`,
                [b.name.trim(), (b.sourceStatement ?? '').trim(), b.textSafeArea, b.layout, stagedFile ?? old.file_name, sha, id, res.locals.userId, old.file_name, image?.width ?? old.width, image?.height ?? old.height]);
            if (!updated.rows.length) {
                if (stagedFile) await unlink(join(directory(), stagedFile));
                stagedFile = undefined;
                res.status(409).json({ error: '底图已被修改或删除，请刷新后重试' }); return;
            }
            const replaced = !!stagedFile;
            stagedFile = undefined; // 数据库已指向新文件，后续清理失败不能删除新文件。
            let filesPending = false;
            if (replaced) await unlink(join(directory(), old.file_name)).catch(e => { if (e.code !== 'ENOENT') filesPending = true; });
            res.json({ asset: personal(updated.rows[0]), files_pending: filesPending });
        } catch (e) {
            if (stagedFile) await unlink(join(directory(), stagedFile)).catch(() => {});
            if ((e as { code?: string }).code === '23505') { res.status(409).json({ error: '相同 GIF 已存在，请直接使用已有底图' }); return; }
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
        let filesPending = false;
        await unlink(join(directory(), result.rows[0].file_name)).catch(e => { if (e.code !== 'ENOENT') filesPending = true; });
        res.json({ ok: true, files_pending: filesPending });
    }
    catch (e) {
        next(e);
    } });
    return router;
}
