import { readFileSync } from 'node:fs';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { newDb, DataType } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, afterEach, expect, it } from 'vitest';
import { exportStickerBundle, importStickerBundle } from './bundle.js';
import { deleteStickerGroupInTransaction } from './deleteGroup.js';
import { SHARED_STICKER_OWNER as OWNER } from './shared.js';
let root: string;
let pools: pg.Pool[];
const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
const sha = createHash('sha256').update(gif).digest('hex');
async function database() {
  const db = newDb();
  db.public.interceptQueries(sql => /^LOCK TABLE /i.test(sql) ? [] : null);
  db.public.registerFunction({name:'trim',args:[DataType.text],returns:DataType.text,implementation:(s:string)=>s.trim()});
  db.public.registerFunction({name:'length',args:[DataType.text],returns:DataType.integer,implementation:(s:string)=>s.length});
  db.public.registerFunction({name:'hashtext',args:[DataType.text],returns:DataType.integer,implementation:()=>1});
  db.public.registerFunction({name:'pg_advisory_xact_lock',args:[DataType.integer],returns:DataType.integer,implementation:()=>1});
  const pool = new (db.adapters.createPg().Pool)() as pg.Pool; pools.push(pool);
  for (const name of ['005_sticker','015_sticker_keywords','018_synthesis_library','030_synthesis_order','019_keyword_gif_removal','024_sticker_group_settings','028_sticker_group_deletion','020_runtime_settings']) {
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

it('关键词组删除记录随清单同步，已有库清理图片，新库不复活，重复导入不重复删除', async () => {
  const origin = await source();
  await exportStickerBundle(origin,root);
  const dest = await database();
  await importStickerBundle(dest,root);
  const db = await origin.connect();
  try {
    await deleteStickerGroupInTransaction(db,{keyword:'来砍我',revision:'00000000-0000-4000-8000-00000000000a'},root);
  } finally { db.release(); }
  await exportStickerBundle(origin,root);
  const data = JSON.parse(await readFile(join(root,'data/sticker-library.json'),'utf8'));
  expect(data.deletedGroups).toEqual([{keyword:'来砍我',revision:'00000000-0000-4000-8000-00000000000a'}]);
  expect(data.stickers).toEqual([]);
  await importStickerBundle(dest,root);
  expect((await dest.query('SELECT * FROM sticker')).rows).toEqual([]);
  expect((await dest.query('SELECT * FROM sticker_group_settings')).rows).toEqual([]);
  const fresh = await database();
  await importStickerBundle(fresh,root);
  expect((await fresh.query('SELECT keyword FROM sticker_group_deletion')).rows).toEqual([{keyword:'来砍我'}]);
  await dest.query("DELETE FROM sticker_group_deletion WHERE keyword='来砍我'");
  await dest.query("INSERT INTO sticker_keyword(user_id,keyword) VALUES($1,'来砍我')",[OWNER]);
  await importStickerBundle(dest,root);
  expect((await dest.query('SELECT * FROM sticker_group_deletion')).rows).toEqual([]);
  expect((await dest.query('SELECT keyword FROM sticker_keyword ORDER BY keyword')).rows).toEqual([{keyword:'来砍我'},{keyword:'空关键词'}]);
});

it('旧格式清单不隐式撤销删除，新清单明确重新添加时才恢复关键词', async () => {
  const origin = await source();
  await exportStickerBundle(origin,root);
  const path = join(root,'data/sticker-library.json');
  const data = JSON.parse(await readFile(path,'utf8'));
  delete data.deletedGroups;
  await writeFile(path,JSON.stringify(data));
  const dest = await database();
  await dest.query('INSERT INTO sticker_group_deletion(keyword,revision) VALUES($1,$2)', ['另一个已删组','00000000-0000-4000-8000-00000000000a']);
  await importStickerBundle(dest,root);
  expect((await dest.query('SELECT keyword FROM sticker_group_deletion')).rows).toEqual([{keyword:'另一个已删组'}]);
});

it('目标本地删组后导入源端无关更新不复活关键词或图片，后续仍可导出', async () => {
  const origin=await source();
  await exportStickerBundle(origin,root);
  const dest=await database();
  await importStickerBundle(dest,root);
  const db=await dest.connect();
  try {await deleteStickerGroupInTransaction(db,{keyword:'来砍我',revision:'00000000-0000-4000-8000-00000000000a'},root);}
  finally {db.release();}
  await origin.query("INSERT INTO sticker_keyword(user_id,keyword) VALUES($1,'另一个新词')",[OWNER]);
  await origin.query('UPDATE sticker SET width=2');
  await exportStickerBundle(origin,root);
  await importStickerBundle(dest,root);
  expect((await dest.query("SELECT keyword FROM sticker_keyword WHERE keyword='来砍我'")).rows).toEqual([]);
  expect((await dest.query('SELECT * FROM sticker')).rows).toEqual([]);
  await expect(exportStickerBundle(dest,root)).resolves.toBeUndefined();
});

it('拒绝非法或同时声明为活动组的删除记录', async () => {
  await exportStickerBundle(await source(),root);
  const path = join(root,'data/sticker-library.json');
  const original = JSON.parse(await readFile(path,'utf8'));
  for (const deletedGroups of [[{keyword:'空关键词',revision:'bad'}], [{keyword:'来砍我',revision:'00000000-0000-4000-8000-00000000000a'}]]) {
    await writeFile(path,JSON.stringify({...original,deletedGroups}));
    await expect(importStickerBundle(await database(),root)).rejects.toThrow(/删除记录/);
  }
});

it('说法从同名原组转出后，导出清单可导入新数据库且不会恢复原组说法', async () => {
  const origin = await source();
  await origin.query('UPDATE sticker_group_settings SET aliases=$1 WHERE keyword=$2', [JSON.stringify([]), '来砍我']);
  await origin.query('INSERT INTO sticker_group_settings(user_id,keyword,aliases) VALUES($1,$2,$3)',
    [OWNER, '空关键词', JSON.stringify(['来砍我'])]);
  await exportStickerBundle(origin, root);
  const dest = await database();
  await importStickerBundle(dest, root);
  await importStickerBundle(dest, root);
  expect((await dest.query('SELECT keyword,aliases FROM sticker_group_settings ORDER BY keyword')).rows).toEqual([
    { keyword: '来砍我', aliases: [] }, { keyword: '空关键词', aliases: ['来砍我'] },
  ]);
  expect((await dest.query('SELECT keywords FROM sticker')).rows).toEqual([{ keywords: '来砍我' }]);
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

it('未分配原图可导出并恢复，空关键词不创建分组且保留原字节与SHA', async () => {
  const origin = await database();
  await writeFile(join(root, 'uploads/stickers/unassigned.gif'), gif);
  await origin.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256,width,height) VALUES($1,'','unassigned.gif','gif',$2,1,1)", [OWNER, sha]);
  await exportStickerBundle(origin, root);
  const manifest = JSON.parse(await readFile(join(root, 'data/sticker-library.json'), 'utf8'));
  expect(manifest.keywords).toEqual([]);
  expect(manifest.settings).toEqual([]);
  expect(manifest.stickers).toEqual([{ fileName: 'unassigned.gif', keywords: '', format: 'gif', width: 1, height: 1, sha256: sha }]);
  const dest = await database();
  await importStickerBundle(dest, root);
  await importStickerBundle(dest, root);
  expect((await dest.query('SELECT keywords,file_name,sha256 FROM sticker')).rows).toEqual([{ keywords: '', file_name: 'unassigned.gif', sha256: sha }]);
  expect((await dest.query('SELECT * FROM sticker_keyword')).rows).toEqual([]);
  expect((await dest.query('SELECT * FROM sticker_group_settings')).rows).toEqual([]);
  expect(await readFile(join(root, 'uploads/stickers/unassigned.gif'))).toEqual(gif);
  await exportStickerBundle(dest, root);
  expect(JSON.parse(await readFile(join(root, 'data/sticker-library.json'), 'utf8'))).toEqual(manifest);
});

it.each(['首次未分配', '分配后变更为未分配'])('源端%s不清空目标已有关键词、次数和排序，多轮清单仍保留', async mode => {
  const origin = await source();
  if (mode === '首次未分配') await origin.query("UPDATE sticker SET keywords=''");
  await exportStickerBundle(origin, root);
  const dest = await database();
  await dest.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256,use_count) VALUES($1,'线上词,第二词','test.gif','gif',$2,42)", [OWNER, sha]);
  await dest.query('INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order) VALUES($1,$2,$3,$4)', [OWNER, '线上词', JSON.stringify(['线上词']), JSON.stringify(['personal:1'])]);
  await importStickerBundle(dest, root);
  expect((await dest.query('SELECT keywords,use_count FROM sticker')).rows[0]).toEqual({
    keywords: mode === '首次未分配' ? '线上词,第二词' : '线上词,第二词,来砍我', use_count: 42,
  });
  // 首次有词导入允许合并；后续用户在线上编辑不可被未分配状态覆盖。
  await dest.query("UPDATE sticker SET keywords='线上词,第二词'");
  const expected = (await dest.query('SELECT id,keywords,use_count FROM sticker')).rows;
  const settings = (await dest.query('SELECT keyword,aliases,asset_order FROM sticker_group_settings ORDER BY keyword')).rows;
  await origin.query("UPDATE sticker SET keywords='',width=2");
  await exportStickerBundle(origin, root);
  await importStickerBundle(dest, root);
  expect((await dest.query('SELECT id,keywords,use_count FROM sticker')).rows).toEqual(expected);
  expect((await dest.query('SELECT width FROM sticker')).rows[0].width).toBe(2);
  await origin.query('UPDATE sticker SET height=3');
  await exportStickerBundle(origin, root);
  await importStickerBundle(dest, root);
  expect((await dest.query('SELECT id,keywords,use_count FROM sticker')).rows).toEqual(expected);
  expect((await dest.query('SELECT keyword,aliases,asset_order FROM sticker_group_settings ORDER BY keyword')).rows).toEqual(settings);
});

it('素材删除标记同步移除目标历史副本，且旧清单不能恢复已删除素材',async()=>{
 const origin=await source(),dest=await database();
 await exportStickerBundle(origin,root);
 const original=await readFile(join(root,'data/sticker-library.json'),'utf8');
 await importStickerBundle(dest,root);
 await dest.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'其他词','duplicate.gif','gif',$2)",[OWNER,sha]);
 await writeFile(join(root,'uploads/stickers/legacy.gif'),gif);
 await dest.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'旧图','legacy.gif','gif',NULL)",[OWNER]);
 await origin.query('DELETE FROM sticker');
 await origin.query('INSERT INTO keyword_gif_removal(user_id,sha256,asset_id) VALUES($1,$2,$3)',[OWNER,sha,`material:${sha}`]);
 await exportStickerBundle(origin,root);
 await importStickerBundle(dest,root);
 expect((await dest.query('SELECT * FROM sticker')).rows).toHaveLength(0);
 await writeFile(join(root,'data/sticker-library.json'),original);
 await importStickerBundle(dest,root);
 expect((await dest.query('SELECT * FROM sticker')).rows).toHaveLength(0);
 expect(await readFile(join(root,'uploads/stickers/test.gif'))).toEqual(gif);
});
