import { readFileSync, mkdirSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
export function stageStickerBundle(repo, revision, serverRoot, uploads) {
  const path = join(serverRoot,'data/sticker-library.json');
  if (!existsSync(path)) return 0;
  const data = JSON.parse(readFileSync(path,'utf8'));
  if (data.version !== 1 || !Array.isArray(data.stickers)) throw Error('图库清单格式错误');
  const verified = data.stickers.map(item=>{
    if (!/^[a-zA-Z0-9_-]+\.(gif|png|jpe?g|webp)$/.test(item.fileName)) throw Error('图库文件名非法');
    const bytes = execFileSync('git',['-C',repo,'show',`${revision}:server/uploads/stickers/${item.fileName}`],{maxBuffer:11*1024*1024,stdio:['pipe','pipe','pipe']});
    if (createHash('sha256').update(bytes).digest('hex') !== item.sha256) throw Error(`图库 SHA256 不匹配：${item.fileName}`);
    const dest = join(uploads,'stickers',item.fileName);
    if (existsSync(dest) && createHash('sha256').update(readFileSync(dest)).digest('hex') !== item.sha256) throw Error(`线上同名图片内容冲突：${item.fileName}`);
    return {dest,bytes};
  });
  mkdirSync(join(uploads,'stickers'),{recursive:true});
  for (const {dest,bytes} of verified) {
    if (existsSync(dest)) continue;
    const temp = `${dest}.${randomUUID()}.tmp`;
    writeFileSync(temp,bytes,{flag:'wx'}); renameSync(temp,dest);
  }
  return verified.length;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [repo,revision,uploads] = process.argv.slice(2);
  if (!repo || !revision || !uploads) throw Error('用法：stage-sticker-bundle.mjs repo revision shared-uploads');
  console.log(`[stickers] staged ${stageStickerBundle(repo,revision,process.cwd(),uploads)} original images`);
}
