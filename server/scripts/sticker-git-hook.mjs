import { readFileSync, existsSync, realpathSync } from 'node:fs';
import { join, sep } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
const git = (...args) => execFileSync('git', args, {maxBuffer:64*1024*1024});
const root = git('rev-parse','--show-toplevel').toString().trim();
const manifest = 'server/data/sticker-library.json';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function files(data) {
  if (data.version !== 1 || !Array.isArray(data.stickers)) throw Error('图库清单格式错误');
  return data.stickers.map(item=>{
    if (!/^[a-zA-Z0-9_-]+\.(gif|png|jpe?g|webp)$/.test(item.fileName) || !/^[a-f0-9]{64}$/.test(item.sha256)) throw Error('图库文件名或SHA256非法');
    return {path:`server/uploads/stickers/${item.fileName}`,sha:item.sha256};
  });
}
try {
  if (process.argv[2] === 'commit') {
    if (!existsSync(join(root,manifest))) process.exit(0);
    const data = JSON.parse(readFileSync(join(root,manifest),'utf8'));
    const selected = files(data);
    for (const f of selected) {
      if (!realpathSync(join(root,f.path)).startsWith(realpathSync(join(root,'server/uploads/stickers'))+sep)) throw Error('图库图片路径越界');
      if (hash(readFileSync(join(root,f.path))) !== f.sha) throw Error(`原图校验失败：${f.path}`);
    }
    git('add','--',manifest,...selected.map(f=>f.path));
  } else if (process.argv[2] === 'push') {
    const head = git('rev-parse','HEAD').toString().trim();
    for (const line of readFileSync(0,'utf8').trim().split('\n').filter(Boolean)) {
      const [, revision] = line.split(/\s+/);
      if (/^0+$/.test(revision)) continue;
      if (!/^[a-f0-9]{40,64}$/.test(revision)) throw Error('推送版本格式错误');
      const paths = git('ls-tree','--name-only',revision,'--',manifest).toString().trim();
      if (!paths) {
        if (revision === head && existsSync(join(root,manifest))) throw Error('图库清单尚未提交，请先 commit 再 push');
        continue;
      }
      const data = JSON.parse(git('show',`${revision}:${manifest}`).toString());
      for (const f of files(data)) if (hash(git('show',`${revision}:${f.path}`)) !== f.sha) throw Error(`推送版本缺少正确原图：${f.path}`);
      if (revision === head && existsSync(join(root,manifest)) && !isDeepStrictEqual(data,JSON.parse(readFileSync(join(root,manifest),'utf8')))) throw Error('后台图库有未提交更新，请先 commit 再 push');
    }
  } else throw Error('用法：sticker-git-hook.mjs commit|push');
} catch (error) {
  console.error(`[stickers] ${error.message}`);
  process.exit(1);
}
