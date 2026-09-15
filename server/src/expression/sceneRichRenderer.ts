import sharp from 'sharp';
import { auditPrototypeGif } from './prototypeAudit.js';
import { assertPrototypeFontAvailable, buildPoseSequence, PROTOTYPE_FONT_PATH } from './prototypeRenderer.js';
import type { ExpressionRenderItem } from './prototypeManifest.js';

export interface SceneRichItem {
  id: string;
  keyword: string;
  caption: string;
  captionPlacement: 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right';
  masterFile: string;
  sourceType: 'ai-original';
}
export interface SceneRichManifest {
  schemaVersion: 1;
  batchId: SceneRichBatchId;
  status: 'review-only';
  items: SceneRichItem[];
}
const BATCH_WORDS = {
  'scene-rich-01': ['我就看看', '真的假的', '让我想想', '溜了'],
  'scene-rich-02': ['你懂的', '细说', '什么事', '原来如此'],
  'scene-rich-03': ['你先跑', '不高兴了', '你认真的', '被你发现了'],
} as const;
export type SceneRichBatchId = keyof typeof BATCH_WORDS;

function isSceneRichBatchId(value: unknown): value is SceneRichBatchId {
  return typeof value === 'string' && Object.hasOwn(BATCH_WORDS, value);
}

export function parseSceneRichBatchArgs(args: string[]): SceneRichBatchId {
  if (args.length === 0) return 'scene-rich-01';
  if (args.length === 2 && args[0] === '--batch' && isSceneRichBatchId(args[1])) return args[1];
  throw new Error('仅接受 --batch scene-rich-01、--batch scene-rich-02 或 --batch scene-rich-03');
}
const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 };

export function validateSceneRichManifest(value: unknown): SceneRichManifest {
  const m = value as Partial<SceneRichManifest> | null;
  if (!m || m.schemaVersion !== 1 || !isSceneRichBatchId(m.batchId) || m.status !== 'review-only' || !Array.isArray(m.items) || m.items.length !== 4) throw new Error('仅接受已登记 scene-rich 批次的四项评审清单');
  const ids = new Set<string>();
  const words = new Set<string>();
  for (const item of m.items) {
    if (!item || typeof item.id !== 'string' || !new RegExp(`^${m.batchId}-[a-z0-9-]+$`).test(item.id) || ids.has(item.id)) throw new Error('id 非法或重复');
    if (!(BATCH_WORDS[m.batchId] as readonly string[]).includes(item.keyword) || words.has(item.keyword) || item.caption !== item.keyword) throw new Error('keyword/caption 必须是本批四词且不重复');
    if (item.masterFile !== `masters/${item.id}.png`) throw new Error('masterFile 必须使用本项固定安全路径');
    if (item.sourceType !== 'ai-original') throw new Error('sourceType 必须是 ai-original');
    if (!['top-left','top-right','bottom-left','bottom-right'].includes(item.captionPlacement)) throw new Error('captionPlacement 非法');
    ids.add(item.id); words.add(item.keyword);
  }
  return m as SceneRichManifest;
}

async function captionOverlay(item: SceneRichItem) {
  await assertPrototypeFontAvailable();
  // 字形由仓库字体确定性栅格化；SVG 只描边，不依赖宿主字体。
  const { data, info } = await sharp({text:{text:item.caption,font:'Droid Sans Fallback 29',fontfile:PROTOTYPE_FONT_PATH,rgba:true}}).png().toBuffer({resolveWithObject:true});
  const alpha = await sharp(data).extractChannel('alpha').png().toBuffer();
  const glyph = await sharp({create:{width:info.width,height:info.height,channels:3,background:'#ffffff'}}).joinChannel(alpha).png().toBuffer();
  const x = item.captionPlacement.endsWith('left') ? 12 : 228-info.width;
  const y = item.captionPlacement.startsWith('top') ? 12 : 228-info.height;
  return sharp(Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240"><defs><filter id="outline" x="-20%" y="-30%" width="140%" height="160%"><feMorphology in="SourceAlpha" operator="dilate" radius="2" result="mask"/><feFlood flood-color="#202020"/><feComposite operator="in" in2="mask"/><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs><image x="${x}" y="${y}" width="${info.width}" height="${info.height}" href="data:image/png;base64,${glyph.toString('base64')}" filter="url(#outline)"/></svg>`)).png().toBuffer();
}

export async function renderSceneRichGif(master: Buffer, item: SceneRichItem) {
  const meta = await sharp(master).metadata();
  if (meta.format !== 'png' || !meta.width || meta.width !== meta.height || meta.width % 2 || meta.width < 480) throw new Error('母版必须为至少480px偶数正方形2×2 PNG');
  const size = meta.width / 2;
  const poses = await Promise.all([0,1,2,3].map(i => sharp(master).extract({left:i%2*size,top:Math.floor(i/2)*size,width:size,height:size}).png().toBuffer()));
  const caption = await captionOverlay(item);
  const frames = await Promise.all(poses.map(async pose => {
    const scene = await sharp(pose).resize(236,236).png().toBuffer();
    return sharp({create:{width:240,height:240,channels:4,background:TRANSPARENT}}).composite([{input:scene,left:2,top:2},{input:caption,left:0,top:0}]).png().toBuffer();
  }));
  const sequence = buildPoseSequence(16);
  const gif = await sharp({create:{width:240,height:240*16,pageHeight:240,channels:4,background:TRANSPARENT}})
    .composite(sequence.map((pose,index)=>({input:frames[pose],left:0,top:index*240})))
    .gif({loop:0,delay:Array(16).fill(100),colours:128,dither:1,effort:10,keepDuplicateFrames:true}).toBuffer();
  const auditItem: ExpressionRenderItem = {id:item.id,keyword:item.keyword,text:item.caption,sourceType:'ai-original',style:'original-life-scene',direction:'core-performance',prompt:'原创完整场景四姿势',motionPreset:'shake',frameCount:16,durationMs:1600,masterFile:item.masterFile,poseFiles:[1,2,3,4].map(n=>`poses/${item.id}/pose-${n}.png`),textPlacement:'center'};
  const audit = await auditPrototypeGif(gif,auditItem);
  if (audit.issues.length) throw new Error(`GIF 审计失败 ${item.id}: ${JSON.stringify(audit.issues)}`);
  const thumbnail = await sharp(gif,{page:0,pages:1}).webp({lossless:true}).toBuffer();
  return { gif, poses, thumbnail, audit };
}
