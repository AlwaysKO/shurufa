import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import { renderSceneRich09Gif, validateSceneRich09Manifest, resolveSceneRich09Master } from '../src/expression/sceneRich09Renderer.js';

// 仅接受明确登记的评审批次，不接受外部输出路径，不能改写正式素材目录。
async function main() {
  if (process.argv.length > 2) throw new Error('本脚本不接受额外参数');
  const batchId = 'scene-rich-09';
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const source = await realpath(join(root, 'assets/expression/batches', batchId));
  const output = join(root, 'artifacts/expression-batches', batchId);
  const manifest = validateSceneRich09Manifest(JSON.parse(await readFile(join(source,'manifest.json'),'utf8')));
  if (manifest.batchId !== batchId) throw new Error('清单批次与所选批次不一致');
  const rendered = [];
  for (const item of manifest.items) {
    const path = await resolveSceneRich09Master(source,item.masterFile);
    rendered.push({item,...await renderSceneRich09Gif(await readFile(path),item)});
  }
  // 所有项审计通过后才写成品，避免审计失败写出半批次（不保证磁盘错误或进程中断时原子发布）。
  await mkdir(join(output,'gifs'),{recursive:true});
  await mkdir(join(output,'thumbnails'),{recursive:true});
  for (const r of rendered) {
    await mkdir(join(source,'poses',r.item.id),{recursive:true});
    await Promise.all(r.poses.map((pose,i)=>writeFile(join(source,'poses',r.item.id,`pose-${i+1}.png`),pose)));
    await writeFile(join(output,'gifs',`${r.item.id}.gif`),r.gif);
    await writeFile(join(output,'thumbnails',`${r.item.id}.webp`),r.thumbnail);
  }
  const report = {batchId:manifest.batchId,status:'review-only',total:4,pass:4,fail:0,expectedTotal:4,humanReview:'pending',items:rendered.map(r=>({...r.item,...r.audit,timeline:r.timeline}))};
  await writeFile(join(output,'report.json'),JSON.stringify(report,null,2)+'\n');
  const sheet = await sharp({create:{width:960,height:240,channels:4,background:'#eeeeee'}}).composite(rendered.map((r,i)=>({input:r.thumbnail,left:i*240,top:0}))).webp({quality:95}).toBuffer();
  await writeFile(join(output,'contact-sheet.webp'),sheet);
  await writeFile(join(output,'preview.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${batchId} 动态评审</title><style>body{font-family:system-ui;background:#ececec;margin:24px}main{display:flex;flex-wrap:wrap;gap:24px}article{background:white;padding:16px;border-radius:12px}img{width:240px;height:240px}p{max-width:960px}</style><h1>完整场景原创 GIF · ${batchId}</h1><p>四词各一张；十二个独立姿势，20帧、4000毫秒、无限循环。开场400毫秒，关键姿势900毫秒；后四姿势共1380毫秒回正；按顺序播放，不倒放、不淡化。仅评审，未加入 APK。帧重复用于停顿，非20个独立姿势。</p><main>${rendered.map(r=>`<article><h2>${r.item.keyword}</h2><img src="gifs/${r.item.id}.gif" alt="${r.item.keyword}"><p>${r.audit.metadata.pages}帧 · ${(r.gif.length/1024).toFixed(1)}KB</p><a href="gifs/${r.item.id}.gif">原始GIF</a></article>`).join('')}</main></html>`);
  console.log(`${batchId}: 4/4 机器通过；人审待确认。${output}`);
}
main().catch(error=>{console.error(error);process.exitCode=1;});
