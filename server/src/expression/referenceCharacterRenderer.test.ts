import { describe, expect, it } from 'vitest';
import sharp from 'sharp';
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { renderReferenceCharacterGif, resolveReferenceMaster } from './referenceCharacterRenderer.js';
const panda = { id: 'panda-really', caption: '真的假的', sourceType: 'user-provided-reference',
  status: 'trial-only', publicationAllowed: false, masterFile: 'masters/panda-really.png' } as const;
async function master(still = false) {
  const xs=[60,65,75,90,110,130,155,180,145,110,80,60];
  const tiles=await Promise.all(xs.map(x=>sharp(Buffer.from(`<svg width="240" height="240"><rect width="240" height="240" fill="white"/><circle cx="${still?60:x}" cy="95" r="26" fill="#222"/></svg>`)).png().toBuffer()));
  return sharp({create:{width:960,height:720,channels:4,background:'white'}}).composite(tiles.map((input,i)=>({input,left:i%4*240,top:Math.floor(i/4)*240}))).png().toBuffer();
}
describe('reference-only character animation',()=>{
 it('renders a 4-second 20-frame trial without fabricating original provenance',async()=>{
  const r=await renderReferenceCharacterGif(await master(),panda);
  expect(r.audit.issues).toEqual([]);
  expect(r.audit.metadata).toMatchObject({width:240,height:240,pages:20,durationMs:4000,loop:0});
  expect(r.poses).toHaveLength(12);
  expect(r.gif.length).toBeLessThan(250*1024);
  expect(r.item).toMatchObject({sourceType:'user-provided-reference',status:'trial-only',publicationAllowed:false});
  expect((await sharp(r.thumbnail).raw().toBuffer()).equals(await sharp(r.gif,{page:0,pages:1}).raw().toBuffer())).toBe(true);
 });
 it('supports only the second approved mushroom caption too',async()=>{
  const r=await renderReferenceCharacterGif(await master(),{...panda,id:'mushroom-think',caption:'让我想想',masterFile:'masters/mushroom-think.png'});
  expect(r.item.caption).toBe('让我想想');
 });
 it('normalizes one-pixel imagegen grid rounding but rejects distorted aspect ratios',async()=>{
  const b=await master();
  const rounded=await sharp(b).resize(1447,1087).png().toBuffer();
  const r=await renderReferenceCharacterGif(rounded,panda);
  expect(r.audit.metadata).toMatchObject({width:240,height:240,pages:20});
  await expect(renderReferenceCharacterGif(await sharp(b).resize(1447,1100).png().toBuffer(),panda)).rejects.toThrow(/母版/);
 });
 it('rejects publication, unknown character, fake provenance and unsafe paths',async()=>{
  const b=await master();
  for(const patch of [{id:'other'},{caption:'乱写'},{sourceType:'ai-original'},{status:'published'},{publicationAllowed:true},{masterFile:'../escape.png'}]){
   await expect(renderReferenceCharacterGif(b,{...panda,...patch})).rejects.toThrow();
  }
 });
 it('rejects static impostors and malformed grid images',async()=>{
  await expect(renderReferenceCharacterGif(await master(true),panda)).rejects.toThrow(/审计/);
  await expect(renderReferenceCharacterGif(await sharp(await master()).resize(600,600).png().toBuffer(),panda)).rejects.toThrow(/母版/);
 });
 it('rejects actual symbolic link escape',async()=>{
  const root=await mkdtemp(join(tmpdir(),'reference-animation-'));
  try{
   const source=join(root,'source');await mkdir(join(source,'masters'),{recursive:true});
   const file=join(source,panda.masterFile);await writeFile(file,'png');
   expect(await resolveReferenceMaster(source,panda.masterFile)).toBe(file);
   await rm(file);const outside=join(root,'outside.png');await writeFile(outside,'png');await symlink(outside,file);
   await expect(resolveReferenceMaster(source,panda.masterFile)).rejects.toThrow(/符号链接越界/);
  }finally{await rm(root,{recursive:true,force:true});}
 });
});

it('renders the four fixed next-batch captions without changing publication boundaries',async()=>{
 for(const [id,caption] of [['panda-talented','真有你的'],['mushroom-learned','学到了'],['panda-refuse','拒绝'],['mushroom-confused','搞不懂']]) {
  const r=await renderReferenceCharacterGif(await master(),{...panda,id,caption,masterFile:`masters/${id}.png`});
  expect(r.item.caption).toBe(caption);expect(r.audit.issues).toEqual([]);
  expect(r.item.publicationAllowed).toBe(false);
 }
});

it('keeps original character provenance separate from meme references',async()=>{
 for(const [id,caption] of [['worker-tired','心累'],['young-man-cheer','加油'],['dog-hopeful','期待']]){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.item.sourceType).toBe('ai-original');expect(r.audit.issues).toEqual([]);
  await expect(renderReferenceCharacterGif(await master(),{...item,sourceType:'user-provided-reference'})).rejects.toThrow();
  await expect(renderReferenceCharacterGif(await master(),{...item,publicationAllowed:true})).rejects.toThrow();
 }
});

it('renders the next three original captions with fixed provenance',async()=>{
 for(const [id,caption] of [['worker-busy','忙着呢'],['young-man-thanks','谢谢'],['dog-hurt','委屈']]){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);expect(r.item.sourceType).toBe('ai-original');
  await expect(renderReferenceCharacterGif(await master(),{...item,caption:'错误字幕'})).rejects.toThrow();
 }
});

it('renders expressive original animal captions with strict source and publication checks',async()=>{
 for(const [id,caption] of [['orange-cat-laugh','笑死'],['long-bill-duck-disdain','嫌弃'],['short-ear-rabbit-yay','好耶']]){
 const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
 const r=await renderReferenceCharacterGif(await master(),item);expect(r.audit.issues).toEqual([]);
 await expect(renderReferenceCharacterGif(await master(),{...item,sourceType:'user-provided-reference'})).rejects.toThrow();
 await expect(renderReferenceCharacterGif(await master(),{...item,publicationAllowed:true})).rejects.toThrow();
 }
});

it('renders the second animal batch without accepting wrong captions or publication',async()=>{
 for(const [id,caption] of [['orange-cat-slack','摸鱼'],['long-bill-duck-shock','震惊'],['rabbit-refuse','拒绝']]){
 const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
 const r=await renderReferenceCharacterGif(await master(),item);expect(r.audit.issues).toEqual([]);
 await expect(renderReferenceCharacterGif(await master(),{...item,caption:'错误'})).rejects.toThrow();
 await expect(renderReferenceCharacterGif(await master(),{...item,publicationAllowed:true})).rejects.toThrow();
 }
});

it('renders four original look styles while preserving provenance and human metadata',async()=>{
 for(const id of ['cat-look','bird-look','seal-look','man-look']){
  const item={...panda,id,caption:'我就看看',masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',publicationAllowed:false});
  if(id==='man-look') expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
});

it('renders four received styles with fixed source and adult male metadata',async()=>{
 for(const id of ['dog-received','bird-received','otter-received','man-received']){
  const item={...panda,id,caption:'收到',masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id==='man-received') expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
});

it('renders four sleepy originals with strict caption source and publication boundaries',async()=>{
 for(const id of ['cat-sleepy','owl-sleepy','penguin-sleepy','man-sleepy']){
  const item={...panda,id,caption:'困了',masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id==='man-sleepy') expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
});

it('renders four speechless originals with fixed caption provenance and publication',async()=>{
 for(const id of ['husky-speechless','frog-speechless','capybara-speechless','man-speechless']){
  const item={...panda,id,caption:'无语',masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id==='man-speechless') expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
});

it('renders four great-news originals with fixed provenance and trial boundaries',async()=>{
 for(const id of ['dog-great','crab-great','pig-great','man-great']){
  const item={...panda,id,caption:'太好了',masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id==='man-great') expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
});

it('renders four hurt originals with fixed provenance and isolated trial status',async()=>{
 for(const id of ['spaniel-hurt','rabbit-hurt','hedgehog-hurt','man-hurt']){
  const item={...panda,id,caption:'委屈',masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id==='man-hurt') expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
});

it('renders twelve volume originals with fixed captions and provenance',async()=>{
 const groups=[['震惊',['cat-shock','frog-shock','owl-shock','man-shock']],['嫌弃',['husky-disdain','duck-disdain','alpaca-disdain','man-disdain']],['得意',['fox-smug','gecko-smug','seal-smug','man-smug']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume02 originals with fixed captions and provenance',async()=>{
 const groups=[['好奇',['siamese-curious','giraffe-curious','raccoon-curious','man-curious']],['生气',['tuxedo-angry','puffer-angry','calf-angry','man-angry']],['偷笑',['shiba-giggle','bird-giggle','otter-giggle','man-giggle']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume03 originals with fixed captions and provenance',async()=>{
 const groups=[['尴尬',['graycat-awkward','turtle-awkward','penguin-awkward','man-awkward']],['期待',['corgi-eager','seal-eager','fawn-eager','man-eager']],['疑惑',['beagle-puzzled','owl-puzzled','panda-puzzled','man-puzzled']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume04 originals with fixed captions and provenance',async()=>{
 const groups=[['害羞',['siamese-shy','mouse-shy','rabbit-shy','man-shy']],['放心',['retriever-relieved','capybara-relieved','sealion-relieved','man-relieved']],['无奈',['husky-helpless','duck-helpless','koala-helpless','man-helpless']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume05 originals with fixed captions and provenance',async()=>{
 const groups=[['傲娇',['whitecat-proud','fox-proud','hamster-proud','man-proud']],['心虚',['dachshund-guilty','puppy-guilty','raccoon-guilty','man-guilty']],['失落',['labrador-downcast','penguin-downcast','bear-downcast','man-downcast']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume06 originals with fixed captions and provenance',async()=>{
 const groups=[['警惕',['tabby-alert','owl-alert','meerkat-alert','man-alert']],['满足',['gingercat-content','otter-content','pig-content','man-content']],['纠结',['collie-torn','rabbit-torn','panda-torn','man-torn']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume07 originals with fixed captions and provenance',async()=>{
 const groups=[['加油',['retriever-encourage','frog-encourage','bear-encourage','man-encourage']],['拜托',['tabby-please','seal-please','hamster-please','man-please']],['冷静',['husky-calm','penguin-calm','capybara-calm','man-calm']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume08 originals with fixed captions and provenance',async()=>{
 const groups=[['不服',['shiba-defiant','duck-defiant','calf-defiant','man-defiant']],['崩溃',['gingercat-overwhelmed','penguin-overwhelmed','sloth-overwhelmed','man-overwhelmed']],['佩服',['raccoon-admire','rabbit-admire','otter-admire','man-admire']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume09 originals with fixed captions and provenance',async()=>{
 const groups=[['忍住',['beagle-restrain','frog-restrain','hamster-restrain','man-restrain']],['迷茫',['tabby-lost','duck-lost','capybara-lost','man-lost']],['没眼看',['retriever-facepalm','penguin-facepalm','bear-facepalm','man-facepalm']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume10 originals with fixed captions and provenance',async()=>{
 const groups=[['饿了',['corgi-hungry','rabbit-hungry','pig-hungry','man-hungry']],['心累',['bulldog-weary','seal-weary','sloth-weary','man-weary']],['嘘',['gingercat-shush','owl-shush','fox-shush','man-shush']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);

it('renders twelve volume11 originals with fixed captions and provenance',async()=>{
 const groups=[['不听',['terrier-refuse','mouse-refuse','otter-refuse','man-refuse']],['懂了',['siamese-understand','owl-understand','raccoon-understand','man-understand']],['紧张',['spaniel-nervous','penguin-nervous','hamster-nervous','man-nervous']]] as const;
 for(const [caption,ids] of groups) for(const id of ids){
  const item={...panda,id,caption,masterFile:`masters/${id}.png`,sourceType:'ai-original'};
  const r=await renderReferenceCharacterGif(await master(),item);
  expect(r.audit.issues).toEqual([]);
  expect(r.item).toMatchObject({sourceType:'ai-original',status:'trial-only',publicationAllowed:false});
  if(id.startsWith('man-')) expect(r.item).toMatchObject({personOrigin:'China',personGender:'male',adult:true});
  for(const patch of [{sourceType:'user-provided-reference'},{publicationAllowed:true},{caption:'错误'}])
   await expect(renderReferenceCharacterGif(await master(),{...item,...patch})).rejects.toThrow();
 }
},30000);
