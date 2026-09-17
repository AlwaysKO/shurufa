import sharp from 'sharp';
import { auditPrototypeGif } from './prototypeAudit.js';
import { assertPrototypeFontAvailable, PROTOTYPE_FONT_PATH } from './prototypeRenderer.js';
import { sceneRich12Timeline, sceneRich12GridBounds, resolveSceneRich12Master } from './sceneRich12Renderer.js';

export const REFERENCE_CHARACTER_ITEMS = [
  {id:'panda-really',caption:'真的假的',masterFile:'masters/panda-really.png'},
  {id:'mushroom-think',caption:'让我想想',masterFile:'masters/mushroom-think.png'},
].map(item=>({...item,sourceType:'user-provided-reference' as const,status:'trial-only' as const,publicationAllowed:false as const}));
export const REFERENCE_CHARACTER_BATCH02_ITEMS = [
  {id:'panda-talented',caption:'真有你的',masterFile:'masters/panda-talented.png'},
  {id:'mushroom-learned',caption:'学到了',masterFile:'masters/mushroom-learned.png'},
  {id:'panda-refuse',caption:'拒绝',masterFile:'masters/panda-refuse.png'},
  {id:'mushroom-confused',caption:'搞不懂',masterFile:'masters/mushroom-confused.png'},
].map(item=>({...item,sourceType:'user-provided-reference' as const,status:'trial-only' as const,publicationAllowed:false as const}));
export const ORIGINAL_CHARACTER_ITEMS = [
  {id:'worker-tired',caption:'心累',masterFile:'masters/worker-tired.png'},
  {id:'young-man-cheer',caption:'加油',masterFile:'masters/young-man-cheer.png'},
  {id:'dog-hopeful',caption:'期待',masterFile:'masters/dog-hopeful.png'},
].map(item=>({...item,sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const}));
export const ORIGINAL_CHARACTER_BATCH02_ITEMS = [
  {id:'worker-busy',caption:'忙着呢',masterFile:'masters/worker-busy.png'},
  {id:'young-man-thanks',caption:'谢谢',masterFile:'masters/young-man-thanks.png'},
  {id:'dog-hurt',caption:'委屈',masterFile:'masters/dog-hurt.png'},
].map(item=>({...item,sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const}));
export const ORIGINAL_ANIMAL_ITEMS = [
  {id:'orange-cat-laugh',caption:'笑死',masterFile:'masters/orange-cat-laugh.png'},
  {id:'long-bill-duck-disdain',caption:'嫌弃',masterFile:'masters/long-bill-duck-disdain.png'},
  {id:'short-ear-rabbit-yay',caption:'好耶',masterFile:'masters/short-ear-rabbit-yay.png'},
].map(item=>({...item,sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const}));
export const ORIGINAL_ANIMAL_BATCH02_ITEMS = [
  {id:'orange-cat-slack',caption:'摸鱼',masterFile:'masters/orange-cat-slack.png'},
  {id:'long-bill-duck-shock',caption:'震惊',masterFile:'masters/long-bill-duck-shock.png'},
  {id:'rabbit-refuse',caption:'拒绝',masterFile:'masters/rabbit-refuse.png'},
].map(item=>({...item,sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const}));
export const ORIGINAL_LOOK_ITEMS = ['cat-look','bird-look','seal-look','man-look'].map(id=>({
  id,caption:'我就看看',masterFile:`masters/${id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(id==='man-look'?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_RECEIVED_ITEMS = ['dog-received','bird-received','otter-received','man-received'].map(id=>({
  id,caption:'收到',masterFile:`masters/${id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(id==='man-received'?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_SLEEPY_ITEMS = ['cat-sleepy','owl-sleepy','penguin-sleepy','man-sleepy'].map(id=>({
  id,caption:'困了',masterFile:`masters/${id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(id==='man-sleepy'?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_SPEECHLESS_ITEMS = ['husky-speechless','frog-speechless','capybara-speechless','man-speechless'].map(id=>({
  id,caption:'无语',masterFile:`masters/${id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(id==='man-speechless'?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_GREAT_ITEMS = ['dog-great','crab-great','pig-great','man-great'].map(id=>({
  id,caption:'太好了',masterFile:`masters/${id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(id==='man-great'?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_HURT_ITEMS = ['spaniel-hurt','rabbit-hurt','hedgehog-hurt','man-hurt'].map(id=>({
  id,caption:'委屈',masterFile:`masters/${id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(id==='man-hurt'?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME_ITEMS = [
  ...['cat-shock','frog-shock','owl-shock','man-shock'].map(id=>({id,caption:'震惊'})),
  ...['husky-disdain','duck-disdain','alpaca-disdain','man-disdain'].map(id=>({id,caption:'嫌弃'})),
  ...['fox-smug','gecko-smug','seal-smug','man-smug'].map(id=>({id,caption:'得意'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME02_ITEMS = [
  ...['siamese-curious','giraffe-curious','raccoon-curious','man-curious'].map(id=>({id,caption:'好奇'})),
  ...['tuxedo-angry','puffer-angry','calf-angry','man-angry'].map(id=>({id,caption:'生气'})),
  ...['shiba-giggle','bird-giggle','otter-giggle','man-giggle'].map(id=>({id,caption:'偷笑'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME03_ITEMS = [
  ...['graycat-awkward','turtle-awkward','penguin-awkward','man-awkward'].map(id=>({id,caption:'尴尬'})),
  ...['corgi-eager','seal-eager','fawn-eager','man-eager'].map(id=>({id,caption:'期待'})),
  ...['beagle-puzzled','owl-puzzled','panda-puzzled','man-puzzled'].map(id=>({id,caption:'疑惑'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME04_ITEMS = [
  ...['siamese-shy','mouse-shy','rabbit-shy','man-shy'].map(id=>({id,caption:'害羞'})),
  ...['retriever-relieved','capybara-relieved','sealion-relieved','man-relieved'].map(id=>({id,caption:'放心'})),
  ...['husky-helpless','duck-helpless','koala-helpless','man-helpless'].map(id=>({id,caption:'无奈'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME05_ITEMS = [
  ...['whitecat-proud','fox-proud','hamster-proud','man-proud'].map(id=>({id,caption:'傲娇'})),
  ...['dachshund-guilty','puppy-guilty','raccoon-guilty','man-guilty'].map(id=>({id,caption:'心虚'})),
  ...['labrador-downcast','penguin-downcast','bear-downcast','man-downcast'].map(id=>({id,caption:'失落'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME06_ITEMS = [
  ...['tabby-alert','owl-alert','meerkat-alert','man-alert'].map(id=>({id,caption:'警惕'})),
  ...['gingercat-content','otter-content','pig-content','man-content'].map(id=>({id,caption:'满足'})),
  ...['collie-torn','rabbit-torn','panda-torn','man-torn'].map(id=>({id,caption:'纠结'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME07_ITEMS = [
  ...['retriever-encourage','frog-encourage','bear-encourage','man-encourage'].map(id=>({id,caption:'加油'})),
  ...['tabby-please','seal-please','hamster-please','man-please'].map(id=>({id,caption:'拜托'})),
  ...['husky-calm','penguin-calm','capybara-calm','man-calm'].map(id=>({id,caption:'冷静'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME08_ITEMS = [
  ...['shiba-defiant','duck-defiant','calf-defiant','man-defiant'].map(id=>({id,caption:'不服'})),
  ...['gingercat-overwhelmed','penguin-overwhelmed','sloth-overwhelmed','man-overwhelmed'].map(id=>({id,caption:'崩溃'})),
  ...['raccoon-admire','rabbit-admire','otter-admire','man-admire'].map(id=>({id,caption:'佩服'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME09_ITEMS = [
  ...['beagle-restrain','frog-restrain','hamster-restrain','man-restrain'].map(id=>({id,caption:'忍住'})),
  ...['tabby-lost','duck-lost','capybara-lost','man-lost'].map(id=>({id,caption:'迷茫'})),
  ...['retriever-facepalm','penguin-facepalm','bear-facepalm','man-facepalm'].map(id=>({id,caption:'没眼看'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME10_ITEMS = [
  ...['corgi-hungry','rabbit-hungry','pig-hungry','man-hungry'].map(id=>({id,caption:'饿了'})),
  ...['bulldog-weary','seal-weary','sloth-weary','man-weary'].map(id=>({id,caption:'心累'})),
  ...['gingercat-shush','owl-shush','fox-shush','man-shush'].map(id=>({id,caption:'嘘'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_VOLUME11_ITEMS = [
  ...['terrier-refuse','mouse-refuse','otter-refuse','man-refuse'].map(id=>({id,caption:'不听'})),
  ...['siamese-understand','owl-understand','raccoon-understand','man-understand'].map(id=>({id,caption:'懂了'})),
  ...['spaniel-nervous','penguin-nervous','hamster-nervous','man-nervous'].map(id=>({id,caption:'紧张'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_WEB01_ITEMS = ['schnauzer-afternoon','seal-afternoon','koala-afternoon','man-afternoon'].map(id=>({
  id,caption:'下午好',masterFile:`masters/${id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_WEB02_ITEMS = [
  ...['siamese-evening','hedgehog-evening','beaver-evening','man-evening'].map(id=>({id,caption:'晚上好'})),
  ...['puppy-noon','sparrow-noon','fawn-noon','man-noon'].map(id=>({id,caption:'中午好'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(item.id.startsWith('man-')?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_WEB03_ITEMS = [
  ...['retriever-reunion','line-reunion','clay-reunion','man-reunion'].map(id=>({id,caption:'好久不见'})),
  ...['cat-welcome','line-welcome','clay-welcome','man-welcome'].map(id=>({id,caption:'欢迎'})),
  ...['rabbit-arrived','line-arrived','clay-arrived','man-arrived'].map(id=>({id,caption:'我来了'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(/^(man|line)-/.test(item.id)?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_WEB04_ITEMS = [
  ...['collie-certain','line-certain','clay-certain','man-certain'].map(id=>({id,caption:'确定'})),
  ...['cat-reassure','line-reassure','clay-reassure','man-reassure'].map(id=>({id,caption:'没关系'})),
  ...['dog-congrats','line-congrats','clay-congrats','man-congrats'].map(id=>({id,caption:'恭喜'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(/^(man|line)-/.test(item.id)?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_WEB05_ITEMS = [
  ...['fox-ofcourse','line-ofcourse','clay-ofcourse','man-ofcourse'].map(id=>({id,caption:'当然'})),
  ...['otter-praised','line-praised','clay-praised','man-praised'].map(id=>({id,caption:'谢谢夸奖'})),
  ...['cat-wow','line-wow','clay-wow','man-wow'].map(id=>({id,caption:'好家伙'})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(/^(man|line)-/.test(item.id)?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const ORIGINAL_WEB06_ITEMS = [
  ...["bird-praise-more","line-praise-more","clay-praise-more","man-praise-more"].map(id=>({id,caption:"谢谢夸奖"})),
  ...["elephant-remember","line-remember","clay-remember","man-remember"].map(id=>({id,caption:"记住了"})),
  ...["owl-then","line-then","clay-then","man-then"].map(id=>({id,caption:"然后呢"})),
].map(item=>({...item,masterFile:`masters/${item.id}.png`,
  sourceType:'ai-original' as const,status:'trial-only' as const,publicationAllowed:false as const,
  ...(/^(man|line)-/.test(item.id)?{personOrigin:'China' as const,personGender:'male' as const,adult:true as const}:{}),
}));
export const resolveReferenceMaster = resolveSceneRich12Master;

function validateItem(value: unknown) {
  const item=value as Record<string,unknown>|null;
  const fixed=[...REFERENCE_CHARACTER_ITEMS,...REFERENCE_CHARACTER_BATCH02_ITEMS,...ORIGINAL_CHARACTER_ITEMS,...ORIGINAL_CHARACTER_BATCH02_ITEMS,...ORIGINAL_ANIMAL_ITEMS,...ORIGINAL_ANIMAL_BATCH02_ITEMS,...ORIGINAL_LOOK_ITEMS,...ORIGINAL_RECEIVED_ITEMS,...ORIGINAL_SLEEPY_ITEMS,...ORIGINAL_SPEECHLESS_ITEMS,...ORIGINAL_GREAT_ITEMS,...ORIGINAL_HURT_ITEMS,...ORIGINAL_VOLUME_ITEMS,...ORIGINAL_VOLUME02_ITEMS,...ORIGINAL_VOLUME03_ITEMS,...ORIGINAL_VOLUME04_ITEMS,...ORIGINAL_VOLUME05_ITEMS,...ORIGINAL_VOLUME06_ITEMS,...ORIGINAL_VOLUME07_ITEMS,...ORIGINAL_VOLUME08_ITEMS,...ORIGINAL_VOLUME09_ITEMS,...ORIGINAL_VOLUME10_ITEMS,...ORIGINAL_VOLUME11_ITEMS,...ORIGINAL_WEB01_ITEMS,...ORIGINAL_WEB02_ITEMS,...ORIGINAL_WEB03_ITEMS,...ORIGINAL_WEB04_ITEMS,...ORIGINAL_WEB05_ITEMS,...ORIGINAL_WEB06_ITEMS].find(candidate=>candidate.id===item?.id);
  if(!item || !fixed || item.caption!==fixed.caption || item.masterFile!==fixed.masterFile
    || item.sourceType!==fixed.sourceType || item.status!=='trial-only' || item.publicationAllowed!==false) {
    throw new Error('只接受固定清单中已确认参考形象的隔离动态试稿，不允许改为正式发布或伪造原创来源');
  }
  return {...fixed};
}

export async function renderReferenceCharacterGif(master: Buffer, value: unknown) {
  const item=validateItem(value);
  const meta=await sharp(master).metadata();
  if(meta.format!=='png'||!meta.width||!meta.height) throw new Error('母版必须为4列3行PNG');
  // imagegen可能返回1447×1087等单像素舍入尺寸；仅归一化到最近的整数4×3方格。
  const gridWidth=Math.round(meta.width/4)*4;
  const gridHeight=gridWidth/4*3;
  if(Math.abs(gridWidth-meta.width)>2 || Math.abs(gridHeight-meta.height)>2) throw new Error('母版网格比例不符');
  const bounds=sceneRich12GridBounds(gridWidth,gridHeight);
  const grid=gridWidth===meta.width&&gridHeight===meta.height ? master
    : await sharp(master).resize(gridWidth,gridHeight).png().toBuffer();
  const poses=await Promise.all(bounds.map(bound=>sharp(grid).extract(bound).png().toBuffer()));
  await assertPrototypeFontAvailable();
  const glyph=await sharp({text:{text:item.caption,font:'Droid Sans Fallback 32',fontfile:PROTOTYPE_FONT_PATH,rgba:true}})
    .png().toBuffer({resolveWithObject:true});
  const strip=await sharp({create:{width:236,height:38,channels:4,background:'white'}})
    .composite([{input:glyph.data,left:Math.floor((236-glyph.info.width)/2),top:Math.floor((38-glyph.info.height)/2)}]).png().toBuffer();
  const clear={r:0,g:0,b:0,alpha:0};
  const frames=await Promise.all(poses.map(async pose=>sharp({create:{width:240,height:240,channels:4,background:clear}})
    .composite([{input:await sharp(pose).flatten({background:'white'}).resize(236,236).png().toBuffer(),left:2,top:2},
      {input:strip,left:2,top:200}]).png().toBuffer()));
  const timeline=sceneRich12Timeline();
  const gif=await sharp({create:{width:240,height:4800,pageHeight:240,channels:4,background:clear}})
    .composite(timeline.map(({pose},i)=>({input:frames[pose],left:0,top:i*240})))
    .gif({loop:0,delay:timeline.map(frame=>frame.delay),colours:128,dither:1,effort:10,keepDuplicateFrames:true}).toBuffer();
  const audit=await auditPrototypeGif(gif,{id:item.id,frameCount:20,durationMs:4000});
  if(audit.issues.length) throw new Error(`GIF审计失败：${JSON.stringify(audit.issues)}`);
  const thumbnail=await sharp(gif,{page:0,pages:1}).webp({lossless:true}).toBuffer();
  return {item,gif,thumbnail,poses,audit,timeline};
}
