import sharp from 'sharp';
import { auditPrototypeGif } from './prototypeAudit.js';
import { assertPrototypeFontAvailable, PROTOTYPE_FONT_PATH } from './prototypeRenderer.js';
import type { ExpressionRenderItem } from './prototypeManifest.js';

export function seriousV2Timeline() {
  const poses = [0,0,1,2,3,4,4,5,5,5,6,6,7,7,8,8];
  const delays = [200,200,180,180,200,160,160,240,230,230,180,180,160,160,170,170];
  return poses.map((pose,i)=>({pose,delay:delays[i]}));
}
export function gridBounds(size: number) {
  return Array.from({length:9},(_,i)=>{
    const col=i%3, row=Math.floor(i/3);
    const left=Math.floor(col*size/3), top=Math.floor(row*size/3);
    return {left,top,width:Math.floor((col+1)*size/3)-left,height:Math.floor((row+1)*size/3)-top};
  });
}
export function insetGridBounds(size: number) {
  return gridBounds(size).map(b=>({left:b.left+2,top:b.top+2,width:b.width-4,height:b.height-4}));
}
export interface SceneRich04Item {
  id: string;
  keyword: string;
  caption: string;
  captionPlacement: 'top-left' | 'bottom-left';
  masterFile: string;
  sourceType: 'ai-original';
}
const SCENE04_ITEMS = [
  ['scene-rich-04-wait', '等一下', 'top-left'],
  ['scene-rich-04-stop', '别闹', 'top-left'],
  ['scene-rich-04-gossip', '吃瓜', 'bottom-left'],
  ['scene-rich-04-laugh', '憋笑', 'bottom-left'],
] as const;
function validateSceneRich04Item(value: unknown): SceneRich04Item {
  const item = value as Partial<SceneRich04Item> | null;
  const expected = SCENE04_ITEMS.find(([id]) => id === item?.id);
  if (!item || !expected || item.keyword !== expected[1] || item.caption !== expected[1]
    || item.captionPlacement !== expected[2] || item.sourceType !== 'ai-original'
    || item.masterFile !== `masters/${expected[0]}.png`) throw new Error('scene-rich-04 项必须匹配固定原创文字、位置与安全路径');
  return item as SceneRich04Item;
}
export function validateSceneRich04Manifest(value: unknown) {
  const m = value as {schemaVersion?: unknown; batchId?: unknown; status?: unknown; items?: unknown[]} | null;
  if (!m || m.schemaVersion !== 1 || m.batchId !== 'scene-rich-04' || m.status !== 'review-only'
    || !Array.isArray(m.items) || m.items.length !== 4) throw new Error('仅接受 scene-rich-04 四项评审清单');
  const items = m.items.map(validateSceneRich04Item);
  if (new Set(items.map(item => item.id)).size !== 4) throw new Error('scene-rich-04 项不能重复');
  return {...m, schemaVersion: 1 as const, batchId: 'scene-rich-04' as const, status: 'review-only' as const, items};
}
export type SceneRich05Item = SceneRich04Item;
const SCENE05_ITEMS = [
  ['scene-rich-05-guess', '你猜', 'top-left'],
  ['scene-rich-05-explain', '听我解释', 'top-left'],
  ['scene-rich-05-disgust', '嫌弃', 'bottom-left'],
  ['scene-rich-05-sleepy', '困了', 'bottom-left'],
] as const;
function validateSceneRich05Item(value: unknown): SceneRich05Item {
  const item = value as Partial<SceneRich05Item> | null;
  const expected = SCENE05_ITEMS.find(([id]) => id === item?.id);
  if (!item || !expected || item.keyword !== expected[1] || item.caption !== expected[1]
    || item.captionPlacement !== expected[2] || item.sourceType !== 'ai-original'
    || item.masterFile !== `masters/${expected[0]}.png`) throw new Error('scene-rich-05 项必须匹配固定原创文字、位置与安全路径');
  return item as SceneRich05Item;
}
export function validateSceneRich05Manifest(value: unknown) {
  const m = value as {schemaVersion?: unknown; batchId?: unknown; status?: unknown; items?: unknown[]} | null;
  if (!m || m.schemaVersion !== 1 || m.batchId !== 'scene-rich-05' || m.status !== 'review-only'
    || !Array.isArray(m.items) || m.items.length !== 4) throw new Error('仅接受 scene-rich-05 四项评审清单');
  const items = m.items.map(validateSceneRich05Item);
  if (new Set(items.map(item => item.id)).size !== 4) throw new Error('scene-rich-05 项不能重复');
  return {...m, schemaVersion: 1 as const, batchId: 'scene-rich-05' as const, status: 'review-only' as const, items};
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


export async function renderSeriousV2Gif(master: Buffer) {
  return renderNinePoseGif(master, {id:'scene-rich-03-serious-v2',keyword:'你认真的',caption:'你认真的',captionPlacement:'bottom-left',masterFile:'master.png',sourceType:'ai-original'});
}
export async function renderSceneRich04Gif(master: Buffer, item: SceneRich04Item) {
  return renderNinePoseGif(master, validateSceneRich04Item(item));
}
export async function renderSceneRich05Gif(master: Buffer, item: SceneRich05Item) {
  return renderNinePoseGif(master, validateSceneRich05Item(item));
}
async function renderNinePoseGif(master: Buffer, config: SceneRich04Item) {
  const meta=await sharp(master).metadata();
  if(meta.format!=='png' || !meta.width || meta.width!==meta.height || meta.width<720) throw new Error('母版必须为至少720px正方形3×3 PNG');
  const poses=await Promise.all(insetGridBounds(meta.width).map(bounds=>sharp(master).extract(bounds).png().toBuffer()));
  const caption=await captionOverlay(config.caption, config.captionPlacement);
  const transparent={r:0,g:0,b:0,alpha:0};
  const frames=await Promise.all(poses.map(async pose=>{
    const scene=await sharp(pose).resize(236,236).png().toBuffer();
    return sharp({create:{width:240,height:240,channels:4,background:transparent}}).composite([{input:scene,left:2,top:2},{input:caption,left:0,top:0}]).png().toBuffer();
  }));
  const timeline=seriousV2Timeline();
  const gif=await sharp({create:{width:240,height:240*16,pageHeight:240,channels:4,background:transparent}})
    .composite(timeline.map(({pose},i)=>({input:frames[pose],left:0,top:i*240})))
    .gif({loop:0,delay:timeline.map(f=>f.delay),colours:128,dither:1,effort:10,keepDuplicateFrames:true}).toBuffer();
  const item:ExpressionRenderItem={id:config.id,keyword:config.keyword,text:config.caption,sourceType:'ai-original',style:'original-life-scene',direction:'core-performance',prompt:'九姿势顺序表演，关键表情停顿后缓慢回正',motionPreset:'shake',frameCount:16,durationMs:3000,masterFile:config.masterFile,poseFiles:poses.map((_,i)=>`poses/pose-${i+1}.png`),textPlacement:'center'};
  const audit=await auditPrototypeGif(gif,item);
  if(audit.issues.length) throw new Error(`GIF 审计失败: ${JSON.stringify(audit.issues)}`);
  const thumbnail=await sharp(gif,{page:0,pages:1}).webp({lossless:true}).toBuffer();
  return {gif,thumbnail,poses,audit,timeline};
}
