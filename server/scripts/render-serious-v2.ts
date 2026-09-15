import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderSeriousV2Gif } from '../src/expression/seriousV2Renderer.js';

async function main() {
  if(process.argv.length>2) throw new Error('独立评审脚本不接受路径或批次参数');
  const batchId='scene-rich-03-serious-v2';
  const root=fileURLToPath(new URL('../../',import.meta.url));
  const source=await realpath(join(root,'assets/expression/batches',batchId));
  const master=await realpath(join(source,'master.png'));
  if(!master.startsWith(source+sep)) throw new Error('母版符号链接越界');
  const result=await renderSeriousV2Gif(await readFile(master));
  const output=join(root,'artifacts/expression-batches',batchId);
  // 审计通过才写成品，不保证进程中断或磁盘错误下的原子发布。
  await mkdir(output,{recursive:true});
  await mkdir(join(source,'poses'),{recursive:true});
  await Promise.all(result.poses.map((pose,i)=>writeFile(join(source,'poses',`pose-${i+1}.png`),pose)));
  await writeFile(join(output,'serious-v2.gif'),result.gif);
  await writeFile(join(output,'thumbnail.webp'),result.thumbnail);
  await writeFile(join(output,'report.json'),JSON.stringify({batchId,status:'review-only',total:1,pass:1,fail:0,humanReview:'pending',independentPoses:9,timeline:result.timeline,items:[result.audit]},null,2)+'\n');
  await writeFile(join(output,'preview.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>你认真的 · 节奏对比</title><style>body{font-family:system-ui;background:#eee;margin:24px}main{display:flex;flex-wrap:wrap;gap:24px}article{background:white;padding:16px;border-radius:12px}img{width:240px;height:240px}p{max-width:800px}</style><h1>你认真的 · 节奏修正版</h1><p>仅评审，未替换旧版、未加入 APK。新版九个独立姿势顺序播放；16 帧包含停顿，不是16个独立姿势。3秒循环，初始400毫秒、质疑眼神700毫秒；无倒放或交叉淡化。请重点检查头部稳定、眼神变化、停顿和循环接缝。</p><main><article><h2>旧版 · 1.6秒</h2><img src="../scene-rich-03/gifs/scene-rich-03-serious.gif" alt="你认真的旧版"><p>四姿势往返</p></article><article><h2>修正版 · 3秒</h2><img src="serious-v2.gif" alt="你认真的修正版"><p>${result.audit.metadata.pages}帧 · ${(result.gif.length/1024).toFixed(1)}KB</p><a href="serious-v2.gif">原始 GIF</a></article></main></html>`);
  console.log(`${batchId}: 1/1 机器通过；人审待确认。${output}`);
}
main().catch(error=>{console.error(error);process.exitCode=1;});
