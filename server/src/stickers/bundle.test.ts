import { readFileSync } from 'node:fs';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { newDb, DataType } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, afterEach, expect, it } from 'vitest';
import { exportStickerBundle, importStickerBundle } from './bundle.js';
import { SHARED_STICKER_OWNER as OWNER } from './shared.js';
let root: string;
let pools: pg.Pool[];
const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
const sha = createHash('sha256').update(gif).digest('hex');
async function database() {
  const db = newDb();
  db.public.registerFunction({name:'trim',args:[DataType.text],returns:DataType.text,implementation:(s:string)=>s.trim()});
  db.public.registerFunction({name:'length',args:[DataType.text],returns:DataType.integer,implementation:(s:string)=>s.length});
  db.public.registerFunction({name:'hashtext',args:[DataType.text],returns:DataType.integer,implementation:()=>1});
  db.public.registerFunction({name:'pg_advisory_xact_lock',args:[DataType.integer],returns:DataType.integer,implementation:()=>1});
  const pool = new (db.adapters.createPg().Pool)() as pg.Pool; pools.push(pool);
  for (const name of ['005_sticker','015_sticker_keywords','018_synthesis_library','019_keyword_gif_removal','024_sticker_group_settings']) {
    await pool.query(readFileSync(new URL(`../../migrations/${name}.sql`,import.meta.url),'utf8').split('-- 兼容历史')[0]);
  }
  await pool.query('CREATE TABLE sticker_bundle_import(singleton BOOLEAN PRIMARY KEY, manifest JSONB NOT NULL)');
  return pool;
}
beforeEach(async()=>{ pools=[]; root=await mkdtemp(join(tmpdir(),'sticker-bundle-')); await mkdir(join(root,'uploads/stickers'),{recursive:true}); });
afterEach(async()=>{ await Promise.all(pools.map(p=>p.end())); await rm(root,{recursive:true,force:true}); });
async function source() {
  const pool=await database();
  await writeFile(join(root,'uploads/stickers/test.gif'),gif);
  await pool.query('INSERT INTO sticker(user_id,keywords,file_name,format,sha256,width,height) VALUES($1,$2,$3,$4,$5,1,1)',[OWNER,'来砍我','test.gif','gif',sha]);
  await pool.query('INSERT INTO sticker_keyword(user_id,keyword) VALUES($1,$2),($1,$3)',[OWNER,'来砍我','空关键词']);
  await pool.query('INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order) VALUES($1,$2,$3,$4)',[OWNER,'来砍我',JSON.stringify(['来砍我','来砍我啊']),JSON.stringify(['system:hello','personal:1'])]);
  return pool;
}
it('导出不包含设备信息；新数据库重映射图片顺序，重复导入不复制、不覆盖线上新增或编辑',async()=>{
  const origin=await source();
  await exportStickerBundle(origin,root);
  const json=await readFile(join(root,'data/sticker-library.json'),'utf8');
  expect(json).not.toContain(OWNER); expect(json).not.toContain('created_at');
  const dest=await database();
  await dest.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'线上独有','online.gif','gif',$2)",[OWNER,sha]);
  await importStickerBundle(dest,root);
  expect((await dest.query('SELECT count(*) FROM sticker')).rows[0].count).toBe(2);
  expect((await dest.query('SELECT asset_order FROM sticker_group_settings')).rows[0].asset_order).toEqual(['system:hello','personal:2']);
  await dest.query("UPDATE sticker SET keywords='线上修改' WHERE file_name='test.gif'");
  await importStickerBundle(dest,root);
  expect((await dest.query("SELECT keywords FROM sticker WHERE file_name='test.gif'")).rows[0].keywords).toBe('线上修改');
  expect((await dest.query('SELECT count(*) FROM sticker_keyword')).rows[0].count).toBe(2);
  await exportStickerBundle(origin,root);
  expect(await readFile(join(root,'data/sticker-library.json'),'utf8')).toBe(json);
});
it('损坏原图拒绝导入，数据库不产生半份图库',async()=>{
  await exportStickerBundle(await source(),root);
  await writeFile(join(root,'uploads/stickers/test.gif'),'broken');
  const dest=await database();
  await expect(importStickerBundle(dest,root)).rejects.toThrow(/SHA256/);
  expect((await dest.query('SELECT count(*) FROM sticker')).rows[0].count).toBe(0);
});
it('拒绝清单路径穿越',async()=>{
  await exportStickerBundle(await source(),root);
  const file=join(root,'data/sticker-library.json');
  const data=JSON.parse(await readFile(file,'utf8')); data.stickers[0].fileName='../secret.gif';
  await writeFile(file,JSON.stringify(data));
  await expect(importStickerBundle(await database(),root)).rejects.toThrow(/文件名/);
});

it('首次导入保留线上同组说法和排序，未设置的本地排序不清空线上排序',async()=>{
  await exportStickerBundle(await source(),root);
  const dest=await database();
  await dest.query('INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order) VALUES($1,$2,$3,$4)',[OWNER,'来砍我',JSON.stringify(['线上说法']),JSON.stringify(['system:online'])]);
  const path=join(root,'data/sticker-library.json'),data=JSON.parse(await readFile(path,'utf8'));data.settings[0].assetOrder=null;await writeFile(path,JSON.stringify(data));
  await importStickerBundle(dest,root);
  expect((await dest.query('SELECT aliases,asset_order FROM sticker_group_settings')).rows[0]).toEqual({aliases:['线上说法','来砍我','来砍我啊'],asset_order:['system:online']});
});
it('导入拒绝线上另一分组已占用的匹配说法',async()=>{
  await exportStickerBundle(await source(),root);
  const dest=await database();
  await dest.query('INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order) VALUES($1,$2,$3,NULL)',[OWNER,'其他组',JSON.stringify(['来砍我啊'])]);
  await expect(importStickerBundle(dest,root)).rejects.toThrow(/说法.*冲突/);
});

it('拉取的新清单尚未导入时，旧数据库不能导出覆盖它',async()=>{
  const origin=await source();await exportStickerBundle(origin,root);
  const path=join(root,'data/sticker-library.json'),data=JSON.parse(await readFile(path,'utf8'));data.keywords.push('另一台电脑的新词');
  const pulled=JSON.stringify(data);await writeFile(path,pulled);
  await expect(exportStickerBundle(origin,root)).rejects.toThrow(/先.*导入/);
  expect(await readFile(path,'utf8')).toBe(pulled);
});

it('首次导入同文件合并关键词，随后只修正尺寸不会覆盖线上关键词',async()=>{
  await exportStickerBundle(await source(),root);
  const dest=await database();
  const legacy='00000000-0000-4000-8000-000000000001';
  await dest.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256,width,height) VALUES($1,'线上关键词','test.gif','gif',$2,1,1)",[legacy,sha]);
  await importStickerBundle(dest,root);
  expect((await dest.query('SELECT user_id,keywords FROM sticker')).rows[0]).toEqual({user_id:legacy,keywords:'线上关键词,来砍我'});
  await dest.query("UPDATE sticker SET keywords='后来线上更新'");
  const path=join(root,'data/sticker-library.json'),data=JSON.parse(await readFile(path,'utf8'));data.stickers[0].width=2;await writeFile(path,JSON.stringify(data));
  await importStickerBundle(dest,root);
  expect((await dest.query('SELECT keywords,width FROM sticker')).rows[0]).toEqual({keywords:'后来线上更新',width:2});
});
