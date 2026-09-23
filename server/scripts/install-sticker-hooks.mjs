import { existsSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync, execFileSync } from 'node:child_process';
const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
if (existsSync(join(root,'.git'))) {
  const current = spawnSync('git',['config','--get','core.hooksPath'],{cwd:root,encoding:'utf8'}).stdout.trim();
  const gitDir = execFileSync('git',['rev-parse','--absolute-git-dir'],{cwd:root,encoding:'utf8'}).trim();
  if ((current && current !== '.githooks') || (!current && ['pre-commit','pre-push'].some(name=>existsSync(join(gitDir,'hooks',name))))) {
    console.warn('[stickers] 已有 Git 钩子，未覆盖。请按 docs/SHARED_STICKER_SYNC.md 将图库检查接入现有钩子。');
  } else {
    execFileSync('git',['config','core.hooksPath','.githooks'],{cwd:root});
    console.log('[stickers] Git 图库自动收集与推送检查已启用');
  }
}
