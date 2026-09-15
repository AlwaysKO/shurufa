import { realpath } from 'node:fs/promises';
import { join, sep } from 'node:path';
import sharp from 'sharp';
import { auditPrototypeGif } from './prototypeAudit.js';
import { assertPrototypeFontAvailable, PROTOTYPE_FONT_PATH } from './prototypeRenderer.js';
import type { ExpressionRenderItem } from './prototypeManifest.js';

export function sceneRich10Timeline() {
  const poses = [0,0,1,2,3,4,5,6,6,7,7,7,7,8,8,9,9,10,10,11];
  const delays = [200,200,220,220,220,220,220,110,110,230,230,220,220,180,170,180,170,170,170,340];
  return poses.map((pose,i)=>({pose,delay:delays[i]}));
}
export function sceneRich10GridBounds(width:number,height:number) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width<960 || height<720 || Math.abs(width-height*4/3)>2) throw new Error('母版必须为至少960×720、4列3行的近4:3 PNG');
  return Array.from({length:12},(_,i)=>{
    const col=i%4,row=Math.floor(i/4);
    const left=Math.floor(col*width/4),top=Math.floor(row*height/3);
    return {left:left+2,top:top+2,width:Math.floor((col+1)*width/4)-left-4,height:Math.floor((row+1)*height/3)-top-4};
  });
}
export interface SceneRich10Item {
  id: string;
  keyword: string;
  caption: string;
  captionPlacement: 'top-left' | 'bottom-left';
  masterFile: string;
  sourceType: 'ai-original';
}
const SCENE10_ITEMS = [
  ['scene-rich-10-offwork', '下班了', 'top-left'],
  ['scene-rich-10-busy', '忙着呢', 'top-left'],
  ['scene-rich-10-satisfied', '满足', 'bottom-left'],
  ['scene-rich-10-puzzled', '疑惑', 'bottom-left'],
] as const;
function validateSceneRich10Item(value: unknown): SceneRich10Item {
  const item = value as Partial<SceneRich10Item> | null;
  const expected = SCENE10_ITEMS.find(([id]) => id === item?.id);
  if (!item || !expected || item.keyword !== expected[1] || item.caption !== expected[1]
    || item.captionPlacement !== expected[2] || item.sourceType !== 'ai-original'
    || item.masterFile !== `masters/${expected[0]}.png`) throw new Error('scene-rich-10 项必须匹配固定原创文字、位置与安全路径');
  return item as SceneRich10Item;
}
export function validateSceneRich10Manifest(value: unknown) {
  const m = value as {schemaVersion?: unknown; batchId?: unknown; status?: unknown; items?: unknown[]} | null;
  if (!m || m.schemaVersion !== 1 || m.batchId !== 'scene-rich-10' || m.status !== 'review-only'
    || !Array.isArray(m.items) || m.items.length !== 4) throw new Error('仅接受 scene-rich-10 四项评审清单');
  const items = m.items.map(validateSceneRich10Item);
  if (new Set(items.map(item => item.id)).size !== 4) throw new Error('scene-rich-10 项不能重复');
  return {...m, schemaVersion: 1 as const, batchId: 'scene-rich-10' as const, status: 'review-only' as const, items};
}
async function captionOverlay(text: string, placement: 'top-left' | 'bottom-left') {
  await assertPrototypeFontAvailable();
  // 字形由仓库字体确定性栅格化；SVG 只描边，不依赖宿主字体。
  const { data, info } = await sharp({text:{text,font:'Droid Sans Fallback 29',fontfile:PROTOTYPE_FONT_PATH,rgba:true}}).png().toBuffer({resolveWithObject:true});
  const alpha = await sharp(data).extractChannel('alpha').png().toBuffer();
  const glyph = await sharp({create:{width:info.width,height:info.height,channels:3,background:'#ffffff'}}).joinChannel(alpha).png().toBuffer();
  const x = 12;
  const y = placement === 'top-left' ? 12 : 228-info.height;
  return sharp(Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240"><defs><filter id="outline" x="-20%" y="-30%" width="140%" height="160%"><feMorphology in="SourceAlpha" operator="dilate" radius="2" result="mask"/><feFlood flood-color="#202020"/><feComposite operator="in" in2="mask"/><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs><image x="${x}" y="${y}" width="${info.width}" height="${info.height}" href="data:image/png;base64,${glyph.toString('base64')}" filter="url(#outline)"/></svg>`)).png().toBuffer();
}


export async function renderSceneRich10Gif(master: Buffer, value: SceneRich10Item) {
  const config=validateSceneRich10Item(value);
  const meta=await sharp(master).metadata();
  if(meta.format!=='png' || !meta.width || !meta.height) throw new Error('母版必须为至少960×720的4列3行 PNG');
  const poses=await Promise.all(sceneRich10GridBounds(meta.width,meta.height).map(bounds=>sharp(master).extract(bounds).png().toBuffer()));
  const caption=await captionOverlay(config.caption, config.captionPlacement);
  const transparent={r:0,g:0,b:0,alpha:0};
  const frames=await Promise.all(poses.map(async pose=>{
    const scene=await sharp(pose).resize(236,236).png().toBuffer();
    return sharp({create:{width:240,height:240,channels:4,background:transparent}}).composite([{input:scene,left:2,top:2},{input:caption,left:0,top:0}]).png().toBuffer();
  }));
  const timeline=sceneRich10Timeline();
  const gif=await sharp({create:{width:240,height:240*20,pageHeight:240,channels:4,background:transparent}})
    .composite(timeline.map(({pose},i)=>({input:frames[pose],left:0,top:i*240})))
    .gif({loop:0,delay:timeline.map(f=>f.delay),colours:128,dither:1,effort:10,keepDuplicateFrames:true}).toBuffer();
  const item:ExpressionRenderItem={id:config.id,keyword:config.keyword,text:config.caption,sourceType:'ai-original',style:'original-life-scene',direction:'core-performance',prompt:'十二姿势顺序表演，关键表情停顿后缓慢回正',motionPreset:'shake',frameCount:20,durationMs:4000,masterFile:config.masterFile,poseFiles:poses.map((_,i)=>`poses/pose-${i+1}.png`),textPlacement:'center'};
  const audit=await auditPrototypeGif(gif,item);
  if(audit.issues.length) throw new Error(`GIF 审计失败: ${JSON.stringify(audit.issues)}`);
  const thumbnail=await sharp(gif,{page:0,pages:1}).webp({lossless:true}).toBuffer();
  return {gif,thumbnail,poses,audit,timeline};
}

export async function resolveSceneRich10Master(source: string, masterFile: string) {
  const root = await realpath(source);
  const path = await realpath(join(root, masterFile));
  if (!path.startsWith(root + sep)) throw new Error('母版符号链接越界');
  return path;
}
