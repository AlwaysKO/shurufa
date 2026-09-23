import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, cpSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { stageStickerBundle } from './stage-sticker-bundle.mjs';
function fixture(t) {
  const root=mkdtempSync(join(tmpdir(),'sticker-git-')); t.after(()=>rmSync(root,{recursive:true,force:true}));
  const git=(...args)=>execFileSync('git',args,{cwd:root,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
  git('init','-q'); git('config','user.name','Test'); git('config','user.email','test@example.invalid');
  for (const folder of ['server/data','server/uploads/stickers','server/scripts','.githooks']) mkdirSync(join(root,folder),{recursive:true});
  for (const file of ['sticker-git-hook.mjs','install-sticker-hooks.mjs']) cpSync(new URL(file,import.meta.url),join(root,'server/scripts',file));
  for (const file of ['pre-commit','pre-push']) cpSync(new URL('../../.githooks/'+file,import.meta.url),join(root,'.githooks',file));
  const bytes=Buffer.from('original GIF fixture');
  const data={version:1,keywords:['来砍我'],stickers:[{fileName:'test.gif',sha256:createHash('sha256').update(bytes).digest('hex')}]};
  const manifest=join(root,'server/data/sticker-library.json');
  writeFileSync(manifest,JSON.stringify(data)); writeFileSync(join(root,'server/uploads/stickers/test.gif'),bytes);
  return {root,git,bytes,data,manifest};
}
test('提交钩子收集完整图库；推送拒绝未提交元数据，完整提交可推送',t=>{
  const {root,git,manifest,data}=fixture(t);
  writeFileSync(join(root,'.env.local'),'PGHOST=invalid-local-database\n');
  execFileSync(process.execPath,['server/scripts/install-sticker-hooks.mjs'],{cwd:root});
  git('add','server/scripts','.githooks'); git('commit','-qm','测试收集图库');
  assert.ok(git('ls-tree','-r','--name-only','HEAD').includes('server/uploads/stickers/test.gif'));
  const revision=git('rev-parse','HEAD');
  const push=()=>spawnSync(process.execPath,['server/scripts/sticker-git-hook.mjs','push'],{cwd:root,encoding:'utf8',input:`refs/heads/main ${revision} refs/heads/main ${'0'.repeat(40)}\n`});
  assert.equal(push().status,0);
  writeFileSync(manifest,JSON.stringify({...data,keywords:['来砍我','新词']}));
  assert.equal(push().status,1); assert.match(push().stderr,/未提交更新/);
});
test('部署读取提交中的原图，不读未提交修改，也不发布无元数据的文件',t=>{
  const {root,git,bytes}=fixture(t);
  writeFileSync(join(root,'server/uploads/stickers/orphan.gif'),'no metadata'); git('add','.'); git('commit','-qm','测试图片');
  writeFileSync(join(root,'server/uploads/stickers/test.gif'),'uncommitted');
  const uploads=join(root,'shared'); assert.equal(stageStickerBundle(root,'HEAD',join(root,'server'),uploads),1);
  assert.deepEqual(readFileSync(join(uploads,'stickers/test.gif')),bytes);
  assert.throws(()=>readFileSync(join(uploads,'stickers/orphan.gif')));
});
test('任意图片缺失或校验错误，部署不复制半份图库、不覆盖线上文件',t=>{
  const {root,git,data,manifest}=fixture(t); git('add','.'); git('commit','-qm','测试完整性');
  writeFileSync(manifest,JSON.stringify({...data,stickers:[...data.stickers,{fileName:'missing.gif',sha256:'0'.repeat(64)}]}));
  const uploads=join(root,'shared');
  assert.throws(()=>stageStickerBundle(root,'HEAD',join(root,'server'),uploads));
  assert.throws(()=>readFileSync(join(uploads,'stickers/test.gif')));
});
