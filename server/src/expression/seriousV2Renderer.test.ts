import { describe, expect, it } from 'vitest';
import sharp from 'sharp';
import { seriousV2Timeline, gridBounds, insetGridBounds, renderSeriousV2Gif, validateSceneRich04Manifest, renderSceneRich04Gif, validateSceneRich05Manifest, renderSceneRich05Gif } from './seriousV2Renderer.js';

async function master(identical = false) {
  const positions = [60,70,90,120,150,180,140,90,60];
  const tiles = await Promise.all(positions.map(x => sharp(Buffer.from(`<svg width="240" height="240"><rect width="240" height="240" fill="#cadaca"/><circle cx="${identical ? 60 : x}" cy="100" r="28" fill="#a23d45"/></svg>`)).png().toBuffer()));
  return sharp({create:{width:720,height:720,channels:4,background:'white'}}).composite(tiles.map((input,i)=>({input,left:i%3*240,top:Math.floor(i/3)*240}))).png().toBuffer();
}

describe('serious v2 review timing', () => {
  it('uses 16 forward-only frames over 3 seconds with readable holds', () => {
    const t = seriousV2Timeline();
    expect(t).toHaveLength(16);
    expect(t.reduce((n,f)=>n+f.delay,0)).toBe(3000);
    expect(t.filter(f=>f.pose===0).reduce((n,f)=>n+f.delay,0)).toBe(400);
    expect(t.filter(f=>f.pose===5).reduce((n,f)=>n+f.delay,0)).toBe(700);
    expect([...new Set(t.map(f=>f.pose))]).toEqual([0,1,2,3,4,5,6,7,8]);
    expect(t.every((f,i)=>!i || f.pose>=t[i-1].pose)).toBe(true);
    expect(t.every(f=>f.delay>0 && f.delay%10===0)).toBe(true);
  });
  it('cuts non-divisible square sizes using floor boundaries without gaps', () => {
    const b = gridBounds(1255);
    expect(b).toHaveLength(9);
    expect(b[0]).toEqual({left:0,top:0,width:418,height:418});
    expect(b[8]).toEqual({left:836,top:836,width:419,height:419});
    expect(b.reduce((n,r)=>n+r.width*r.height,0)).toBe(1255*1255);
  });
  it('insets each floor-aligned cell by two pixels to remove separators', () => {
    expect(insetGridBounds(1255)[0]).toEqual({left:2,top:2,width:414,height:414});
    expect(insetGridBounds(1255)[8]).toEqual({left:838,top:838,width:415,height:415});
  });
  it('renders independently posed GIF and actual first-frame fallback', async () => {
    const result = await renderSeriousV2Gif(await master());
    expect(result.poses).toHaveLength(9);
    expect(result.audit.issues).toEqual([]);
    expect(result.audit.metadata).toMatchObject({width:240,height:240,pages:16,loop:0,durationMs:3000,loopClosed:true});
    expect(result.audit.metadata.delays).toEqual(seriousV2Timeline().map(f=>f.delay));
    expect(result.gif.length).toBeLessThanOrEqual(250*1024);
    expect(await sharp(result.thumbnail).ensureAlpha().raw().toBuffer()).toEqual(await sharp(result.gif,{page:0,pages:1}).ensureAlpha().raw().toBuffer());
  });
  it('rejects invalid masters and static impostors without weakening audit', async () => {
    await expect(renderSeriousV2Gif(await sharp(await master()).resize(719,720).png().toBuffer())).rejects.toThrow(/母版/);
    await expect(renderSeriousV2Gif(await sharp(await master()).jpeg().toBuffer())).rejects.toThrow(/母版/);
    await expect(renderSeriousV2Gif(await master(true))).rejects.toThrow(/审计/);
  });
});

const scene04 = () => ({schemaVersion:1,batchId:'scene-rich-04',status:'review-only',items:[
  ['wait','等一下','top-left'],['stop','别闹','top-left'],['gossip','吃瓜','bottom-left'],['laugh','憋笑','bottom-left'],
].map(([suffix,keyword,captionPlacement])=>({id:`scene-rich-04-${suffix}`,keyword,caption:keyword,captionPlacement,masterFile:`masters/scene-rich-04-${suffix}.png`,sourceType:'ai-original',generation:{tool:'image_gen.imagegen'}}))});

describe('scene rich 04 review-only nine-pose batch',()=>{
  it('accepts the exact four original items and preserves provenance',()=>{
    const value=scene04();
    expect(validateSceneRich04Manifest(value)).toEqual(value);
  });
  it('rejects unrelated batches, missing or duplicate items, mismatched text and unsafe paths',()=>{
    for(const mutate of [
      (m:ReturnType<typeof scene04>)=>{m.batchId='scene-rich-03';},
      (m:ReturnType<typeof scene04>)=>{m.status='published';},
      (m:ReturnType<typeof scene04>)=>{m.items.pop();},
      (m:ReturnType<typeof scene04>)=>{m.items[1]=m.items[0];},
      (m:ReturnType<typeof scene04>)=>{m.items[0].keyword='你好';},
      (m:ReturnType<typeof scene04>)=>{m.items[0].caption='别闹';},
      (m:ReturnType<typeof scene04>)=>{m.items[0].captionPlacement='bottom-left';},
      (m:ReturnType<typeof scene04>)=>{m.items[0].masterFile='../master.png';},
      (m:ReturnType<typeof scene04>)=>{m.items[0].sourceType='licensed';},
    ]) {const value=scene04();mutate(value);expect(()=>validateSceneRich04Manifest(value)).toThrow();}
    for(const bad of [null,{},[],{...scene04(),items:[null,null,null,null]}]) expect(()=>validateSceneRich04Manifest(bad)).toThrow();
  });
  it('renders the registered caption with nine real poses and approved timing',async()=>{
    const item=validateSceneRich04Manifest(scene04()).items[0];
    const r=await renderSceneRich04Gif(await master(),item);
    expect(r.audit.id).toBe(item.id);
    expect(r.poses).toHaveLength(9);
    expect(r.audit.issues).toEqual([]);
    expect(r.audit.metadata).toMatchObject({width:240,height:240,pages:16,loop:0,durationMs:3000});
    expect(r.timeline).toEqual(seriousV2Timeline());
    expect(await sharp(r.thumbnail).ensureAlpha().raw().toBuffer()).toEqual(await sharp(r.gif,{page:0,pages:1}).ensureAlpha().raw().toBuffer());
    await expect(renderSceneRich04Gif(await master(true),item)).rejects.toThrow(/审计/);
    await expect(renderSceneRich04Gif(await master(),{...item,caption:'任意文字'})).rejects.toThrow();
  });
});

const scene05 = () => ({schemaVersion:1,batchId:'scene-rich-05',status:'review-only',items:[
  ['guess','你猜','top-left'],['explain','听我解释','top-left'],['disgust','嫌弃','bottom-left'],['sleepy','困了','bottom-left'],
].map(([suffix,keyword,captionPlacement])=>({id:`scene-rich-05-${suffix}`,keyword,caption:keyword,captionPlacement,masterFile:`masters/scene-rich-05-${suffix}.png`,sourceType:'ai-original',generation:{tool:'image_gen.imagegen'}}))});

describe('scene rich 05 review-only nine-pose batch',()=>{
  it('accepts the exact four original items and preserves provenance',()=>{
    const value=scene05();
    expect(validateSceneRich05Manifest(value)).toEqual(value);
    expect(() => validateSceneRich04Manifest(value)).toThrow();
    expect(() => validateSceneRich05Manifest(scene04())).toThrow();
  });
  it('rejects unrelated batches, missing or duplicate items, mismatched text and unsafe paths',()=>{
    for(const mutate of [
      (m:ReturnType<typeof scene05>)=>{m.batchId='scene-rich-04';},
      (m:ReturnType<typeof scene05>)=>{m.status='published';},
      (m:ReturnType<typeof scene05>)=>{m.items.pop();},
      (m:ReturnType<typeof scene05>)=>{m.items[1]=m.items[0];},
      (m:ReturnType<typeof scene05>)=>{m.items[0].keyword='你好';},
      (m:ReturnType<typeof scene05>)=>{m.items[0].caption='别闹';},
      (m:ReturnType<typeof scene05>)=>{m.items[0].captionPlacement='bottom-left';},
      (m:ReturnType<typeof scene05>)=>{m.items[0].masterFile='../master.png';},
      (m:ReturnType<typeof scene05>)=>{m.items[0].sourceType='licensed';},
    ]) {const value=scene05();mutate(value);expect(()=>validateSceneRich05Manifest(value)).toThrow();}
    for(const bad of [null,{},[],{...scene05(),items:[null,null,null,null]}]) expect(()=>validateSceneRich05Manifest(bad)).toThrow();
  });
  it('renders the registered caption with nine real poses and approved timing',async()=>{
    const item=validateSceneRich05Manifest(scene05()).items[0];
    const r=await renderSceneRich05Gif(await master(),item);
    expect(r.audit.id).toBe(item.id);
    expect(r.poses).toHaveLength(9);
    expect(r.audit.issues).toEqual([]);
    expect(r.audit.metadata).toMatchObject({width:240,height:240,pages:16,loop:0,durationMs:3000});
    expect(r.timeline).toEqual(seriousV2Timeline());
    expect(await sharp(r.thumbnail).ensureAlpha().raw().toBuffer()).toEqual(await sharp(r.gif,{page:0,pages:1}).ensureAlpha().raw().toBuffer());
    await expect(renderSceneRich05Gif(await master(true),item)).rejects.toThrow(/审计/);
    await expect(renderSceneRich05Gif(await master(),{...item,caption:'任意文字'})).rejects.toThrow();
  });
});
