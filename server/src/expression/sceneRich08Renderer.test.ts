import { describe, expect, it } from 'vitest';
import sharp from 'sharp';
import { sceneRich08Timeline, sceneRich08GridBounds, renderSceneRich08Gif, validateSceneRich08Manifest } from './sceneRich08Renderer.js';
const item = {id:'scene-rich-08-arrange',keyword:'安排',caption:'安排',captionPlacement:'top-left' as const,masterFile:'masters/scene-rich-08-arrange.png',sourceType:'ai-original' as const};
const render = (b:Buffer) => renderSceneRich08Gif(b,item);

async function master(identical = false) {
  const positions = [60,65,75,90,110,130,155,180,145,110,80,60];
  const tiles = await Promise.all(positions.map(x => sharp(Buffer.from(`<svg width="240" height="240"><rect width="240" height="240" fill="#cadaca"/><circle cx="${identical ? 60 : x}" cy="100" r="28" fill="#a23d45"/></svg>`)).png().toBuffer()));
  return sharp({create:{width:960,height:720,channels:4,background:'white'}}).composite(tiles.map((input,i)=>({input,left:i%4*240,top:Math.floor(i/4)*240}))).png().toBuffer();
}

describe('scene rich 08 review timing', () => {
  it('uses 20 forward-only frames over 4 seconds with readable holds', () => {
    const t = sceneRich08Timeline();
    expect(t).toHaveLength(20);
    expect(t.reduce((n,f)=>n+f.delay,0)).toBe(4000);
    expect(t.filter(f=>f.pose===0).reduce((n,f)=>n+f.delay,0)).toBe(400);
    expect(t.filter(f=>f.pose===7).reduce((n,f)=>n+f.delay,0)).toBe(900);
    expect([...new Set(t.map(f=>f.pose))]).toEqual([0,1,2,3,4,5,6,7,8,9,10,11]);
    expect(t.every((f,i)=>!i || f.pose>=t[i-1].pose)).toBe(true);
    expect(t.every(f=>f.delay>0 && f.delay%10===0)).toBe(true);
  });
  it('cuts four by three near-square cells and rejects invalid master dimensions',()=>{
    expect(sceneRich08GridBounds(960,720)).toHaveLength(12);
    expect(sceneRich08GridBounds(960,720)[11]).toEqual({left:722,top:482,width:236,height:236});
    expect(sceneRich08GridBounds(1255,941)[11]).toEqual({left:943,top:629,width:310,height:310});
    expect(()=>sceneRich08GridBounds(960,960)).toThrow(/母版/);
    expect(()=>sceneRich08GridBounds(800,600)).toThrow(/母版/);
    const t=sceneRich08Timeline();
    expect(t.filter(f=>f.pose>=8).reduce((n,f)=>n+f.delay,0)).toBe(1380);
  });
  it('renders independently posed GIF and actual first-frame fallback', async () => {
    const result = await render(await master());
    expect(result.poses).toHaveLength(12);
    expect(result.audit.issues).toEqual([]);
    expect(result.audit.metadata).toMatchObject({width:240,height:240,pages:20,loop:0,durationMs:4000,loopClosed:true});
    expect(result.audit.metadata.delays).toEqual(sceneRich08Timeline().map(f=>f.delay));
    expect(result.gif.length).toBeLessThanOrEqual(250*1024);
    expect(await sharp(result.thumbnail).ensureAlpha().raw().toBuffer()).toEqual(await sharp(result.gif,{page:0,pages:1}).ensureAlpha().raw().toBuffer());
  });
  it('rejects invalid masters and static impostors without weakening audit', async () => {
    await expect(render(await sharp(await master()).resize(719,720).png().toBuffer())).rejects.toThrow(/母版/);
    await expect(render(await sharp(await master()).jpeg().toBuffer())).rejects.toThrow(/母版/);
    await expect(render(await master(true))).rejects.toThrow(/审计/);
  });
});


const manifest=()=>({schemaVersion:1,batchId:'scene-rich-08',status:'review-only',items:[
 ['arrange','安排','top-left'],['calm','别急','top-left'],['hungry','饿了','bottom-left'],['awkward','尴尬','bottom-left'],
].map(([suffix,keyword,captionPlacement])=>({...item,id:`scene-rich-08-${suffix}`,keyword,caption:keyword,captionPlacement,masterFile:`masters/scene-rich-08-${suffix}.png`,generation:{tool:'image_gen.imagegen'}}))});
it('accepts only four fixed review originals and keeps provenance',()=>{
 expect(validateSceneRich08Manifest(manifest())).toEqual(manifest());
 for(const bad of [null,{}, {...manifest(),batchId:'scene-rich-05'}, {...manifest(),status:'published'}, {...manifest(),items:[item]}, {...manifest(),items:Array(4).fill(item)}]) expect(()=>validateSceneRich08Manifest(bad)).toThrow();
 for(const change of [{caption:'错误'},{keyword:'错误'},{captionPlacement:'bottom-left'},{masterFile:'../master.png'},{sourceType:'licensed'}]){
 const m=manifest();Object.assign(m.items[0],change);expect(()=>validateSceneRich08Manifest(m)).toThrow();
 }
});
