import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import sharp from 'sharp';
import { ORIGINAL_WEB01_ITEMS, ORIGINAL_VOLUME11_ITEMS, ORIGINAL_VOLUME10_ITEMS, ORIGINAL_VOLUME09_ITEMS, ORIGINAL_VOLUME08_ITEMS, ORIGINAL_VOLUME07_ITEMS, ORIGINAL_VOLUME06_ITEMS, ORIGINAL_VOLUME05_ITEMS, ORIGINAL_VOLUME04_ITEMS, ORIGINAL_VOLUME03_ITEMS, ORIGINAL_VOLUME02_ITEMS, ORIGINAL_VOLUME_ITEMS, ORIGINAL_HURT_ITEMS, ORIGINAL_GREAT_ITEMS, ORIGINAL_SPEECHLESS_ITEMS, ORIGINAL_SLEEPY_ITEMS, ORIGINAL_RECEIVED_ITEMS, ORIGINAL_LOOK_ITEMS, REFERENCE_CHARACTER_ITEMS, REFERENCE_CHARACTER_BATCH02_ITEMS, ORIGINAL_CHARACTER_ITEMS, ORIGINAL_CHARACTER_BATCH02_ITEMS, ORIGINAL_ANIMAL_ITEMS, ORIGINAL_ANIMAL_BATCH02_ITEMS, renderReferenceCharacterGif, resolveReferenceMaster } from '../src/expression/referenceCharacterRenderer.js';
import { publishDirectoryAtomically } from '../src/expression/prototypePublication.js';

async function main() {
  if(process.argv.length>3 || (process.argv[2] && !['batch02','original01','original02','animals01','animals02','look01','received01','sleepy01','speechless01','great01','hurt01','volume01','volume02','volume03','volume04','volume05','volume06','volume07','volume08','volume09','volume10','volume11','web01'].includes(process.argv[2]))) throw new Error('仅接受固定批次 batch02 / original01 / original02 / animals01 / animals02 / look01 / received01 / sleepy01 / speechless01 / great01 / hurt01 / volume01 / volume02 / volume03 / volume04 / volume05 / volume06 / volume07 / volume08 / volume09 / volume10 / volume11 / web01');
  const web01=process.argv[2]==='web01';
  const volume11=process.argv[2]==='volume11';
  const volume10=process.argv[2]==='volume10';
  const volume09=process.argv[2]==='volume09';
  const volume08=process.argv[2]==='volume08';
  const volume07=process.argv[2]==='volume07';
  const volume06=process.argv[2]==='volume06';
  const volume05=process.argv[2]==='volume05';
  const volume04=process.argv[2]==='volume04';
  const volume03=process.argv[2]==='volume03';
  const volume02=process.argv[2]==='volume02';
  const volume=process.argv[2]==='volume01';
  const hurt=process.argv[2]==='hurt01';
  const great=process.argv[2]==='great01';
  const speechless=process.argv[2]==='speechless01';
  const sleepy=process.argv[2]==='sleepy01';
  const received=process.argv[2]==='received01';
  const look=process.argv[2]==='look01';
  const animalSecond=process.argv[2]==='animals02';
  const animals=animalSecond || process.argv[2]==='animals01';
  const originalSecond=process.argv[2]==='original02';
  const original=web01 || volume11 || volume10 || volume09 || volume08 || volume07 || volume06 || volume05 || volume04 || volume03 || volume02 || volume || hurt || great || speechless || sleepy || received || look || animals || originalSecond || process.argv[2]==='original01';
  const second=process.argv[2]==='batch02';
  const items=web01?ORIGINAL_WEB01_ITEMS:volume11?ORIGINAL_VOLUME11_ITEMS:volume10?ORIGINAL_VOLUME10_ITEMS:volume09?ORIGINAL_VOLUME09_ITEMS:volume08?ORIGINAL_VOLUME08_ITEMS:volume07?ORIGINAL_VOLUME07_ITEMS:volume06?ORIGINAL_VOLUME06_ITEMS:volume05?ORIGINAL_VOLUME05_ITEMS:volume04?ORIGINAL_VOLUME04_ITEMS:volume03?ORIGINAL_VOLUME03_ITEMS:volume02?ORIGINAL_VOLUME02_ITEMS:volume?ORIGINAL_VOLUME_ITEMS:hurt?ORIGINAL_HURT_ITEMS:great?ORIGINAL_GREAT_ITEMS:speechless?ORIGINAL_SPEECHLESS_ITEMS:sleepy?ORIGINAL_SLEEPY_ITEMS:received?ORIGINAL_RECEIVED_ITEMS:look?ORIGINAL_LOOK_ITEMS:animalSecond?ORIGINAL_ANIMAL_BATCH02_ITEMS:animals?ORIGINAL_ANIMAL_ITEMS:originalSecond?ORIGINAL_CHARACTER_BATCH02_ITEMS:original?ORIGINAL_CHARACTER_ITEMS:second?REFERENCE_CHARACTER_BATCH02_ITEMS:REFERENCE_CHARACTER_ITEMS;
  const root=fileURLToPath(new URL(`../../artifacts/expression-character-trials/${web01?'web-original-01-animated':volume11?'original-volume-11-animated':volume10?'original-volume-10-animated':volume09?'original-volume-09-animated':volume08?'original-volume-08-animated':volume07?'original-volume-07-animated':volume06?'original-volume-06-animated':volume05?'original-volume-05-animated':volume04?'original-volume-04-animated':volume03?'original-volume-03-animated':volume02?'original-volume-02-animated':volume?'original-volume-01-animated':hurt?'original-hurt-01-animated':great?'original-great-01-animated':speechless?'original-speechless-01-animated':sleepy?'original-sleepy-01-animated':received?'original-received-01-animated':look?'original-look-01-animated':animals?`original-animals-${animalSecond?'02':'01'}-animated`:original?`original-characters-${originalSecond?'02':'01'}-animated`:`reference-${second?'02':'01'}-animated`}/`,import.meta.url));
  const rendered: Awaited<ReturnType<typeof renderReferenceCharacterGif>>[]=[];
  for(const item of items) {
    rendered.push(await renderReferenceCharacterGif(await readFile(await resolveReferenceMaster(root,item.masterFile)),item));
  }
  // 只原子替换隔离成品目录，永不触及APK、runtime或正式源清单。
  await publishDirectoryAtomically(join(root,'output'),async output=>{
    await mkdir(join(output,'gifs'));await mkdir(join(output,'thumbnails'));await mkdir(join(output,'poses'));
    for(const r of rendered) {
      await writeFile(join(output,'gifs',r.item.id+'.gif'),r.gif);
      await writeFile(join(output,'thumbnails',r.item.id+'.webp'),r.thumbnail);
      await mkdir(join(output,'poses',r.item.id));
      await Promise.all(r.poses.map((pose,i)=>writeFile(join(output,'poses',r.item.id,`${i+1}.png`),pose)));
    }
    const report={status:'trial-only',sourceType:original?'ai-original':'user-provided-reference',licenseStatus:original?'pending-review':'unverified',publicationAllowed:false,
      staticCharacterReview:(web01 || volume11 || volume10 || volume09 || volume08 || volume07 || volume06 || volume05 || volume04 || volume03 || volume02 || volume || hurt || great || speechless || sleepy || received || look)?'pending':'approved',humanAnimationReview:'pending',total:rendered.length,pass:rendered.length,fail:0,
      items:rendered.map(r=>({...r.item,...r.audit,timeline:r.timeline}))};
    await writeFile(join(output,'report.json'),JSON.stringify(report,null,2)+'\n');
    await sharp({create:{width:240*rendered.length,height:240,channels:4,background:'white'}})
      .composite(rendered.map((r,i)=>({input:r.thumbnail,left:i*240,top:0}))).webp({quality:95}).toFile(join(output,'contact-sheet.webp'));
    await writeFile(join(output,'preview.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${(web01 || volume11 || volume10 || volume09 || volume08 || volume07 || volume06 || volume05 || volume04 || volume03 || volume02 || volume || hurt || great || speechless || sleepy || received || look)?'形象与动态待审·原创试稿':'已确认形象·动态试稿'}</title><style>body{font-family:system-ui;background:#eee;padding:24px}main{display:flex;gap:24px;flex-wrap:wrap}article{background:white;padding:16px}img{width:240px;height:240px}</style><h1>${original?'原创角色':'熊猫头 / 蘑菇头'}动态试稿</h1><p>每张4秒、20帧，12格姿势顺序播放。${(web01 || volume11 || volume10 || volume09 || volume08 || volume07 || volume06 || volume05 || volume04 || volume03 || volume02 || volume || hurt || great || speechless || sleepy || received || look)?'形象与动态均待审':'形象已确认，动态效果待审'}；未加入APK或接口。</p><main>${rendered.map(r=>`<article><h2>${r.item.caption}</h2><img src="gifs/${r.item.id}.gif" alt="${r.item.caption}"><p>${(r.gif.length/1024).toFixed(1)} KiB</p><a href="gifs/${r.item.id}.gif">原始GIF</a></article>`).join('')}</main></html>`);
  });
  console.log(`参考形象动态试稿 ${rendered.length}/${rendered.length} 机器审计通过；${join(root,'output')}`);
}
main().catch(error=>{console.error(error);process.exitCode=1;});
