import { readFileSync, realpathSync } from 'node:fs';
import { mkdir, readFile, readdir, rm, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { expect, it } from 'vitest';
import { importMaterial } from './materials.js';
import { withGroupLock } from '../api/stickerLibrary.js';
import { SHARED_STICKER_OWNER as OWNER } from './shared.js';
const cluster=process.env.STICKER_SYNC_TEST_CLUSTER;
if(cluster&&(!cluster.startsWith('/tmp/shurufa-sticker-test.')||readFileSync(join(cluster,'test-instance-only'),'utf8')!=='sticker-sync-only')) throw Error('非独立测试实例');
const test=cluster?it:it.skip;
test('真实PG并发导入按内容只保存一次，事务回滚只清理本请求文件',async()=>{
 const schema='materials_'+randomUUID().replaceAll('-','');
 const pool=new pg.Pool({host:join(cluster!,'socket'),port:5433,user:'sticker_test',database:'sticker_sync_test',options:`-c search_path=${schema}`});
 const root=join(cluster!,schema),gif=Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
 try {
  expect(realpathSync((await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir)).toBe(realpathSync(join(cluster!,'data')));
  await pool.query(`CREATE SCHEMA ${schema}`); await pool.query(readFileSync(new URL('../../migrations/005_sticker.sql',import.meta.url),'utf8')); await pool.query('ALTER TABLE sticker ADD COLUMN sha256 TEXT');
  await pool.query(readFileSync(new URL('../../migrations/019_keyword_gif_removal.sql',import.meta.url),'utf8'));
  await mkdir(root,{recursive:true});
  const run=async()=>{ const files:string[]=[]; return withGroupLock(pool,OWNER,db=>importMaterial(db,{buffer:gif,filename:'original.gif'},files,root)); };
  const results=await Promise.all([run(),run()]); expect(results.map(r=>r.status).sort()).toEqual(['existing','imported']);
  expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(1);
  const original=(await readdir(join(root,'uploads/stickers')))[0]; expect(await readFile(join(root,'uploads/stickers',original))).toEqual(gif);
  const files:string[]=[];
  const other=Buffer.from(gif); other[13]=127;
  await expect(withGroupLock(pool,OWNER,async db=>{await importMaterial(db,{buffer:other,filename:'other.gif'},files,root);throw Error('forced rollback');})).rejects.toThrow('forced rollback');
  expect(files).toHaveLength(1); await Promise.all(files.map(path=>unlink(path)));
  expect(await readdir(join(root,'uploads/stickers'))).toEqual([original]); expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(1);
 }finally{await pool.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);await pool.end();await rm(root,{recursive:true,force:true});}
});

test('真实PG批量删除回滚保持记录与删除标记一致',async()=>{
 const {deleteMaterials}=await import('./materials.js');
 const schema='material_delete_'+randomUUID().replaceAll('-','');
 const pool=new pg.Pool({host:join(cluster!,'socket'),port:5433,user:'sticker_test',database:'sticker_sync_test',options:`-c search_path=${schema}`});
 const root=join(cluster!,schema),gif=Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
 try {
  expect(realpathSync((await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir)).toBe(realpathSync(join(cluster!,'data')));
  await pool.query(`CREATE SCHEMA ${schema}`);
  for(const name of ['005_sticker','015_sticker_keywords','019_keyword_gif_removal','028_sticker_group_deletion']) await pool.query(readFileSync(new URL(`../../migrations/${name}.sql`,import.meta.url),'utf8'));
  await pool.query('ALTER TABLE sticker ADD COLUMN sha256 TEXT');
  const result=await withGroupLock(pool,OWNER,db=>importMaterial(db,{buffer:gif,filename:'test.gif'},[],root));
  const body={confirm:'DELETE',sha256s:[result.material!.sha256]};
  await expect(withGroupLock(pool,OWNER,async db=>{await deleteMaterials(db,body,root);throw Error('forced rollback');})).rejects.toThrow('forced rollback');
  expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(1);
  expect((await pool.query('SELECT * FROM keyword_gif_removal')).rows).toHaveLength(0);
  await withGroupLock(pool,OWNER,db=>deleteMaterials(db,body,root));
  expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(0);
  expect((await pool.query('SELECT * FROM keyword_gif_removal')).rows).toHaveLength(1);
  expect(await readFile(join(root,result.material!.url))).toEqual(gif);
 } finally {await pool.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);await pool.end();await rm(root,{recursive:true,force:true});}
});
