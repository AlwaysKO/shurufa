import { readFileSync } from 'node:fs';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const A = '00000000-0000-4000-8000-00000000000a', B = '00000000-0000-4000-8000-00000000000b';
const gif = readFileSync(new URL('../../../assets/expression/templates/blank-cat-side-eye.gif', import.meta.url));
let pool: pg.Pool, root: string, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>;
const body = () => ({ file_base64: gif.toString('base64'), filename: 'cat.gif', name: '猫', noTextConfirmed: true, rightsConfirmed: true, sourceStatement: '本人拥有用于本产品的授权', textSafeArea: { x: 6, y: 190, width: 228, height: 44 }, layout: { minFontSize: 12, maxFontSize: 24, textColor: '#222222', strokeColor: '#ffffff', strokeWidth: 1, alignment: 'center', maxLines: 2 } });
beforeEach(async () => { pool = new (newDb().adapters.createPg().Pool)(); for (const f of ['005_sticker.sql', '018_synthesis_library.sql', '019_keyword_gif_removal.sql'])
    await pool.query(readFileSync(new URL(`../../migrations/${f}`, import.meta.url), 'utf8')); root = await mkdtemp(join(tmpdir(), 'synthesis-api-')); vi.spyOn(process, 'cwd').mockReturnValue(root); await mkdir(join(root, '.runtime/expression-assets'), { recursive: true }); await writeFile(join(root, '.runtime/expression-assets/catalog.json'), JSON.stringify({ version: 'system-v1', templates: [], emojiBases: [], emojiCombinations: [] })); app = createApp(pool); agent = await authenticatedRequest(app); });
afterEach(async () => { vi.restoreAllMocks(); await pool?.end(); if (root)
    await rm(root, { recursive: true, force: true }); });
it('无字上传独立存储、并发SHA去重、隔离所有者、完整目录和删除更新版本', async () => { const initial = await request(app).get('/api/v1/mobile/expressions/versions').set('X-Device-Id', A); expect(initial.status).toBe(200); const uploads = await Promise.all([1, 2].map(() => agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(body()))); expect(uploads.map(r => r.status).sort()).toEqual([200, 201]); const asset = uploads[0].body.asset; expect(asset.sourceType).toBe('owner-upload'); expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(0); const catalog = await request(app).get('/api/v1/mobile/expressions/catalog').set('X-Device-Id', A); expect(catalog.body).toMatchObject({ complete: true, templates: [{ id: asset.id, type: 'synthesis-template', embeddedText: null }] }); expect(catalog.body.version).not.toBe(initial.body.version); expect((await request(app).get(asset.url).set('X-Device-Id', B)).status).toBe(404); expect((await request(app).get(asset.url).set('X-Device-Id', A)).status).toBe(200); expect((await agent.delete(`/api/v1/dashboard/synthesis-library/${asset.id}?user_id=${B}`)).status).toBe(404); const deleted = await agent.delete(`/api/v1/dashboard/synthesis-library/${asset.id}?user_id=${A}`); expect(deleted.status).toBe(200); expect((await request(app).get('/api/v1/mobile/expressions/versions').set('X-Device-Id', A)).body.version).toBe(initial.body.version); });
it.each(['noTextConfirmed', 'rightsConfirmed', 'sourceStatement', 'textSafeArea', 'layout'])('缺少 %s 拒绝上传', async (key) => { const input: any = body(); delete input[key]; expect((await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(input)).status).toBe(400); });
it('拒绝伪造GIF与越界安全区', async () => { for (const input of [{ ...body(), file_base64: Buffer.from('GIF89a').toString('base64') }, { ...body(), textSafeArea: { x: 200, y: 200, width: 100, height: 100 } }])
    expect((await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(input)).status).toBe(400); });
it('关键词个人图进入完整推荐目录，不捏造来源和文字，元数据变更改变版本但不读取图片', async () => { await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,width,height,sha256) VALUES($1,'干嘛','missing.gif','gif',240,240,$2)", [A, 'a'.repeat(64)]); const url = '/api/v1/mobile/expressions'; const version = (await request(app).get(`${url}/versions`).set('X-Device-Id', A)).body.version; const catalog = (await request(app).get(`${url}/catalog`).set('X-Device-Id', A)).body; expect(catalog.templates[0]).toMatchObject({ id: 'sticker-1', sourceType: 'owner-upload', embeddedText: null, keywords: ['干嘛'], version: 'a'.repeat(64) }); expect((await request(app).get(`${url}/recommend?q=干嘛`).set('X-Device-Id', A)).body.results[0].id).toBe('sticker-1'); expect((await request(app).get(`${url}/catalog`).set('X-Device-Id', B)).body.templates).toEqual([]); await pool.query("UPDATE sticker SET keywords='你好'"); expect((await request(app).get(`${url}/versions`).set('X-Device-Id', A)).body.version).not.toBe(version); });
it('与系统SHA重复时不建立个人副本，并过滤推荐用途', async () => { const sha = (await import('node:crypto')).createHash('sha256').update(gif).digest('hex'); const sys = { id: 'blank-system', type: 'synthesis-template', format: 'gif', version: 'v1', fileName: 'templates/a.gif', thumbnailFileName: null, sha256: sha, width: 240, height: 240, keywords: ['干嘛'], emotions: [], embeddedText: null, textSafeArea: body().textSafeArea, layout: body().layout, heat: 0 }; await writeFile(join(root, '.runtime/expression-assets/catalog.json'), JSON.stringify({ version: 'sys2', templates: [sys], emojiBases: [], emojiCombinations: [] })); const upload = await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(body()); expect(upload.status).toBe(200); expect(upload.body).toMatchObject({ duplicate: true, asset: { id: 'blank-system', source: 'system', deletable: false } }); expect((await pool.query('SELECT * FROM synthesis_asset')).rows).toHaveLength(0); });
it('版本不随使用计数改变、随系统目录改变；版本接口没有GIF读取依赖', async () => { await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'你好','does-not-exist.gif','gif',$2)", [A, 'a'.repeat(64)]); const get = () => request(app).get('/api/v1/mobile/expressions/versions').set('X-Device-Id', A); const initial = await get(); await pool.query('UPDATE sticker SET use_count=42'); expect((await get()).body).toEqual(initial.body); await writeFile(join(root, '.runtime/expression-assets/catalog.json'), JSON.stringify({ version: 'changed', templates: [], emojiBases: [], emojiCombinations: [] })); expect((await get()).body.version).not.toBe(initial.body.version); });
it('未登录与缺少CSRF保护头不能上传', async () => { expect((await request(app).post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(body())).status).toBe(401); });
it('拒绝静态GIF和非240尺寸，字节不变的重复上传不改变版本', async () => { const sharp = (await import('sharp')).default; const png = await sharp({ create: { width: 240, height: 240, channels: 4, background: 'white' } }).gif().toBuffer(); expect((await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send({ ...body(), file_base64: png.toString('base64') })).status).toBe(400); await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(body()); const path = '/api/v1/mobile/expressions/versions'; const before = (await request(app).get(path).set('X-Device-Id', A)).body; await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(body()); expect((await request(app).get(path).set('X-Device-Id', A)).body).toEqual(before); });
it('推荐上传计算真实SHA与尺寸，拒绝扩展名伪造，手机立即获得关键词', async () => { const invalid = await agent.post(`/api/v1/dashboard/stickers?user_id=${A}`).send({ filename: 'bad.gif', file_base64: Buffer.from('not an image').toString('base64'), keywords: '坏图' }); expect(invalid.status).toBe(400); const uploaded = await agent.post(`/api/v1/dashboard/stickers?user_id=${A}`).send({ filename: 'ok.gif', file_base64: gif.toString('base64'), keywords: '干嘛', width: 9999, height: 9999 }); expect(uploaded.status).toBe(201); const snap = (await request(app).get('/api/v1/mobile/expressions/catalog').set('X-Device-Id', A)).body; expect(snap.templates[0]).toMatchObject({ width: 240, height: 240, keywords: ['干嘛'] }); expect(snap.templates[0].sha256).toMatch(/^[a-f0-9]{64}$/); });
it('后续系统图库吸收同SHA个人底图时完整目录仍只下发一次', async () => {
 const uploaded=await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(body());
 const asset={...uploaded.body.asset,id:'system-promoted',sourceType:'ai-original',fileName:'templates/promoted.gif'};
 await writeFile(join(root,'.runtime/expression-assets/catalog.json'),JSON.stringify({version:'promoted',templates:[asset],emojiBases:[],emojiCombinations:[]}));
 const response=await request(app).get('/api/v1/mobile/expressions/catalog').set('X-Device-Id',A);
 expect(response.body.templates).toHaveLength(1);
 expect(response.body.templates[0].id).toBe('system-promoted');
 const listing=await agent.get(`/api/v1/dashboard/synthesis-library?user_id=${A}`);
 expect(listing.body.assets).toHaveLength(1);
 expect(listing.body.assets[0].id).toBe('system-promoted');
});

it('关键词图删除标记不能删掉同SHA的独立合成底图', async () => {
 const uploaded=await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(body());
 expect(uploaded.status).toBe(201);
 const asset=uploaded.body.asset;
 await pool.query('INSERT INTO keyword_gif_removal(user_id,sha256,asset_id) VALUES($1,$2,$3)',[A,asset.sha256,'keyword-same-bytes']);
 const snapshot=await request(app).get('/api/v1/mobile/expressions/catalog').set('X-Device-Id',A);
 expect(snapshot.status).toBe(200);
 expect(snapshot.body.templates.map((a:any)=>a.id)).toContain(asset.id);
});

it.each([{width:13,height:14},{width:14,height:13},{width:1,height:1}])('安全区%s容不下含描边的最小字时拒绝且不写库', async area => {
 const input={...body(),textSafeArea:{x:0,y:0,...area}};
 const response=await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(input);
 expect(response.status).toBe(400);
 expect(response.body.error).toContain('至少容纳一个最小字');
 expect((await pool.query('SELECT * FROM synthesis_asset')).rows).toHaveLength(0);
});
it.each([{minFontSize:12,strokeWidth:1,size:14},{minFontSize:18,strokeWidth:3,size:24}])('安全区按最小字体与描边计算边界 %s，不要求容纳最大行数', async ({minFontSize,strokeWidth,size}) => {
 const input={...body(),textSafeArea:{x:0,y:0,width:size,height:size},layout:{...body().layout,minFontSize,strokeWidth,maxLines:6}};
 expect((await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send({...input,textSafeArea:{...input.textSafeArea,width:size-1}})).status).toBe(400);
 expect((await agent.post(`/api/v1/dashboard/synthesis-library?user_id=${A}`).send(input)).status).toBe(201);
});
