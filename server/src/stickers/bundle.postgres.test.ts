import { readFileSync, realpathSync, readdirSync } from 'node:fs';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import pg from 'pg';
import { expect, it, vi } from 'vitest';
import { createHash, randomUUID } from 'node:crypto';
import { deleteDeviceData } from '../lib/deleteDeviceData.js';
import { exportStickerBundle, importStickerBundle } from './bundle.js';
import { SHARED_STICKER_OWNER as OWNER } from './shared.js';
import { deleteStickerGroup, deleteStickerGroupInTransaction } from './deleteGroup.js';
import { loadStickerLibrary, stickerAssetKey } from '../api/stickerLibrary.js';
import { access } from 'node:fs/promises';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const cluster=process.env.STICKER_SYNC_TEST_CLUSTER;
if(cluster && (!cluster.startsWith('/tmp/shurufa-sticker-test.') || readFileSync(join(cluster,'test-instance-only'),'utf8')!=='sticker-sync-only')) throw Error('非独立测试实例');
const test=cluster?it:it.skip;
test('真实PostgreSQL迁移历史归属、空库恢复和JSONB重排后重复部署均正确',async()=>{
  const schema = 'test_'+randomUUID().replaceAll('-','');
  const pool=new pg.Pool({host:join(cluster!,'socket'),port:5433,user:'sticker_test',database:'sticker_sync_test',options:`-c search_path=${schema}`});
  try {
    expect(realpathSync((await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir)).toBe(realpathSync(join(cluster!,'data')));
    await pool.query(`CREATE SCHEMA ${schema}`);
    const root=join(cluster!,schema); await mkdir(join(root,'uploads/stickers'),{recursive:true});
    const gif=Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
    await writeFile(join(root,'uploads/stickers/legacy.gif'),gif);
    for(const name of readdirSync(new URL('../../migrations/',import.meta.url)).filter(n=>n.endsWith('.sql')&&n<'027').sort()) await pool.query(readFileSync(new URL('../../migrations/'+name,import.meta.url),'utf8'));
    const old='00000000-0000-4000-8000-000000000001',sha=createHash('sha256').update(gif).digest('hex');
    await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'来砍我','legacy.gif','gif',$2)",[old,sha]);
    await pool.query("INSERT INTO sticker_keyword(user_id,keyword) VALUES($1,'来砍我')",[old]);
    await pool.query("INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order) VALUES($1,'来砍我',$2,$3)",[old,JSON.stringify(['来砍我啊']),JSON.stringify(['personal:1'])]);
    await pool.query("INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order) VALUES($1,'来砍我',$2,$3)",['00000000-0000-4000-8000-000000000002',JSON.stringify(['历史另一个说法']),JSON.stringify(['system:legacy'])]);
    const migration=readFileSync(new URL('../../migrations/027_shared_sticker_library.sql',import.meta.url),'utf8');
    await pool.query(migration); await pool.query(migration);
    await pool.query(readFileSync(new URL('../../migrations/028_sticker_group_deletion.sql',import.meta.url),'utf8'));
    expect((await pool.query('SELECT user_id FROM sticker')).rows[0].user_id).toBe(old);
    await pool.query("INSERT INTO device(id,name) VALUES($1,'原上传手机')",[old]);
    await deleteDeviceData(pool,old);
    expect((await pool.query('SELECT count(*) FROM sticker')).rows[0].count).toBe('1');
    expect((await pool.query('SELECT aliases FROM sticker_group_settings WHERE user_id=$1',[OWNER])).rows[0].aliases).toEqual(['来砍我啊','历史另一个说法']);
    expect((await pool.query('SELECT count(*) FROM sticker_group_settings')).rows[0].count).toBe('3');
    await exportStickerBundle(pool,root);
    await pool.query('TRUNCATE sticker,sticker_keyword,sticker_group_settings,sticker_bundle_import RESTART IDENTITY');
    await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'线上独有','other.gif','gif')",[OWNER]);
    await importStickerBundle(pool,root);
    expect((await pool.query('SELECT asset_order FROM sticker_group_settings')).rows[0].asset_order).toEqual(['personal:2','system:legacy']);
    await pool.query("UPDATE sticker SET keywords='在线修改',use_count=12 WHERE file_name='legacy.gif'");
    await importStickerBundle(pool,root);
    expect((await pool.query("SELECT keywords,use_count FROM sticker WHERE file_name='legacy.gif'")).rows[0]).toEqual({keywords:'在线修改',use_count:'12'});
    // 空别名数组必须保留，NULL排序不能变成空数组。
    const file=join(root,'data/sticker-library.json'), data=JSON.parse(await readFile(file,'utf8'));
    data.settings[0].aliases=[]; data.settings[0].assetOrder=null;
    await writeFile(file,JSON.stringify(data)); await importStickerBundle(pool,root);
    expect((await pool.query('SELECT aliases,asset_order FROM sticker_group_settings')).rows[0]).toEqual({aliases:[],asset_order:null});
    expect((await pool.query('SELECT count(*) FROM sticker')).rows[0].count).toBe('2');
    await pool.query("INSERT INTO sticker_group_settings(user_id,keyword,aliases) VALUES($1,'其他组',$2)",[OWNER,JSON.stringify(['冲突说法'])]);
    data.settings[0].aliases=['冲突说法'];
    data.stickers.push({...data.stickers[0],fileName:'new.gif'});
    await writeFile(join(root,'uploads/stickers/new.gif'),gif); await writeFile(file,JSON.stringify(data));
    await expect(importStickerBundle(pool,root)).rejects.toThrow(/说法.*冲突/);
    expect((await pool.query('SELECT count(*) FROM sticker')).rows[0].count).toBe('2');
    expect((await pool.query('SELECT aliases FROM sticker_group_settings WHERE keyword=$1',['来砍我'])).rows[0].aliases).toEqual([]);
  } finally {await pool.end();}
});

test('真实PostgreSQL组删除提交后清理独占文件，共用图保留，失败事务不丢图',async()=>{
  const schema='test_'+randomUUID().replaceAll('-','');
  const pool=new pg.Pool({host:join(cluster!,'socket'),port:5433,user:'sticker_test',database:'sticker_sync_test',options:`-c search_path=${schema}`});
  try {
    expect(realpathSync((await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir)).toBe(realpathSync(join(cluster!,'data')));
    await pool.query(`CREATE SCHEMA ${schema}`);
    for(const file of readdirSync(new URL('../../migrations/',import.meta.url)).filter(n=>n.endsWith('.sql')).sort()) await pool.query(readFileSync(new URL('../../migrations/'+file,import.meta.url),'utf8'));
    const root=join(cluster!,schema); await mkdir(join(root,'uploads/stickers'),{recursive:true});
    vi.spyOn(process,'cwd').mockReturnValue(root);
    for (const name of ['solo.gif','shared.gif']) await writeFile(join(root,'uploads/stickers',name),'GIF89a');
    await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'待删','solo.gif','gif'),($1,'待删,保留','shared.gif','gif')",[OWNER]);
    const group=(await loadStickerLibrary(pool,OWNER,root)).groups.find(g=>g.keyword==='待删')!;
    const body={confirm:'DELETE',aliases:group.aliases,assetKeys:group.assets.map(stickerAssetKey)};
    await expect(deleteStickerGroup(pool,'待删',{...body,assetKeys:[]})).rejects.toThrow(/已变化/);
    expect((await pool.query('SELECT COUNT(*) FROM sticker')).rows[0].count).toBe('2');
    await expect(access(join(root,'uploads/stickers/solo.gif'))).resolves.toBeUndefined();
    expect(await deleteStickerGroup(pool,'待删',body)).toEqual({keyword:'待删',files_pending:false});
    await expect(access(join(root,'uploads/stickers/solo.gif'))).rejects.toMatchObject({code:'ENOENT'});
    await expect(access(join(root,'uploads/stickers/shared.gif'))).resolves.toBeUndefined();
    expect((await pool.query('SELECT keywords,file_name FROM sticker')).rows).toEqual([{keywords:'保留',file_name:'shared.gif'}]);
    expect((await pool.query("SELECT * FROM runtime_setting WHERE key LIKE 'device_delete_files:%'")).rows).toEqual([]);
    await expect(deleteStickerGroup(pool,'待删',body)).rejects.toThrow(/已删除/);
    await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'坏路径','../outside.gif','gif')",[OWNER]);
    const bad=(await loadStickerLibrary(pool,OWNER,root)).groups.find(g=>g.keyword==='坏路径')!;
    await expect(deleteStickerGroup(pool,'坏路径',{confirm:'DELETE',aliases:bad.aliases,assetKeys:bad.assets.map(stickerAssetKey)})).rejects.toThrow(/路径异常/);
    expect((await pool.query("SELECT file_name FROM sticker WHERE keywords='坏路径'")).rows).toEqual([{file_name:'../outside.gif'}]);
    expect((await pool.query("SELECT * FROM sticker_group_deletion WHERE keyword='坏路径'")).rows).toEqual([]);
    const agent = await authenticatedRequest(createApp(pool));
    await pool.query("INSERT INTO sticker_keyword(user_id,keyword) VALUES($1,'并发删除')",[OWNER]);
    const blocker = await pool.connect();
    try {
      await blocker.query('BEGIN');
      await blocker.query('SELECT pg_advisory_xact_lock(hashtext($1))',[`sticker-groups:${OWNER}`]);
      const uploading = agent.post('/api/v1/dashboard/stickers').send({group_keyword:'并发删除',filename:'race.gif',file_base64:'R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'}).then(response=>response);
      let waiting = false;
      for (let i=0;i<100;i++) {
        waiting = Number((await pool.query("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory'")).rows[0].count)>0;
        if (waiting) break;
        await new Promise(resolve=>setTimeout(resolve,10));
      }
      expect(waiting).toBe(true);
      await deleteStickerGroupInTransaction(blocker,{keyword:'并发删除',revision:randomUUID()},root);
      await blocker.query('COMMIT');
      expect((await uploading).status).toBe(409);
      expect((await pool.query("SELECT * FROM sticker WHERE keywords='并发删除'")).rows).toEqual([]);
    } finally {await blocker.query('ROLLBACK');blocker.release();}
    await pool.query("DELETE FROM sticker WHERE keywords='坏路径'");
    await writeFile(join(root,'uploads/stickers/sync.gif'),'GIF89a');
    await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'同步删除','sync.gif','gif')",[OWNER]);
    await exportStickerBundle(pool,root);
    const path=join(root,'data/sticker-library.json');
    const data=JSON.parse(await readFile(path,'utf8'));
    data.keywords=data.keywords.filter((keyword:string)=>keyword!=='同步删除');
    data.stickers=data.stickers.filter((sticker:any)=>sticker.fileName!=='sync.gif');
    data.deletedGroups.push({keyword:'同步删除',revision:randomUUID()});
    await writeFile(path,JSON.stringify(data));
    await importStickerBundle(pool,root);
    await expect(access(join(root,'uploads/stickers/sync.gif'))).rejects.toMatchObject({code:'ENOENT'});
    await expect(access(join(root,'uploads/stickers/shared.gif'))).resolves.toBeUndefined();
    expect((await pool.query("SELECT * FROM sticker WHERE keywords='同步删除'")).rows).toEqual([]);
  } finally {vi.restoreAllMocks(); await pool.end();}
});
