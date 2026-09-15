import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';
import sharp from 'sharp';
import { parseSceneRichBatchArgs, renderSceneRichGif, validateSceneRichManifest } from './sceneRichRenderer.js';

const words = ['我就看看', '真的假的', '让我想想', '溜了'];
const manifest = () => ({ schemaVersion: 1, batchId: 'scene-rich-01', status: 'review-only', items: words.map((keyword, i) => ({ id: `scene-rich-01-${i}`, keyword, caption: keyword, captionPlacement: 'top-left', masterFile: `masters/scene-rich-01-${i}.png`, sourceType: 'ai-original' })) });
async function master(identical = false) {
  const tiles = await Promise.all([0,1,2,3].map(i => sharp(Buffer.from(`<svg width="256" height="256"><rect width="256" height="256" fill="#cceedd"/><circle cx="${identical ? 80 : 50+i*40}" cy="140" r="38" fill="#c43b41"/></svg>`)).png().toBuffer()));
  return sharp({ create: { width:512,height:512,channels:4,background:'white' } }).composite(tiles.map((input,i)=>({input,left:i%2*256,top:Math.floor(i/2)*256}))).png().toBuffer();
}
describe('scene-rich review renderer', () => {
  it('accepts only the explicit four-word review batch', () => {
    expect(validateSceneRichManifest(manifest()).items).toHaveLength(4);
    for (const patch of [{batchId:'daily-01'},{status:'published'},{items:manifest().items.slice(1)}]) expect(()=>validateSceneRichManifest({...manifest(),...patch})).toThrow();
  });
  it('accepts scene-rich-02 with its own four words and rejects cross-batch mixing', () => {
    const m = manifest();
    m.batchId = 'scene-rich-02';
    m.items = ['你懂的', '细说', '什么事', '原来如此'].map((keyword, i) => ({ ...m.items[i], id: `scene-rich-02-${i}`, masterFile: `masters/scene-rich-02-${i}.png`, keyword, caption: keyword }));
    expect(validateSceneRichManifest(m).batchId).toBe('scene-rich-02');
    for (const patch of [{keyword:'溜了',caption:'溜了'}, {id:'scene-rich-01-0',masterFile:'masters/scene-rich-01-0.png'}]) {
      expect(()=>validateSceneRichManifest({...m,items:[{...m.items[0],...patch},...m.items.slice(1)]})).toThrow();
    }
    expect(()=>validateSceneRichManifest({...m,batchId:'scene-rich-04'})).toThrow();
  });
  it('accepts scene-rich-03 and rejects old words and cross-batch IDs', () => {
    const m = manifest();
    m.batchId = 'scene-rich-03';
    m.items = ['你先跑', '不高兴了', '你认真的', '被你发现了'].map((keyword, i) => ({ ...m.items[i], id: `scene-rich-03-${i}`, masterFile: `masters/scene-rich-03-${i}.png`, keyword, caption: keyword }));
    expect(validateSceneRichManifest(m).batchId).toBe('scene-rich-03');
    for (const patch of [
      {keyword:'溜了',caption:'溜了'},
      {keyword:'你懂的',caption:'你懂的'},
      {id:'scene-rich-01-0',masterFile:'masters/scene-rich-01-0.png'},
      {id:'scene-rich-02-0',masterFile:'masters/scene-rich-02-0.png'},
    ]) {
      expect(()=>validateSceneRichManifest({...m,items:[{...m.items[0],...patch},...m.items.slice(1)]})).toThrow();
    }
  });
  it('selects only explicit CLI batches with backward-compatible default', () => {
    expect(parseSceneRichBatchArgs([])).toBe('scene-rich-01');
    expect(parseSceneRichBatchArgs(['--batch','scene-rich-01'])).toBe('scene-rich-01');
    expect(parseSceneRichBatchArgs(['--batch','scene-rich-02'])).toBe('scene-rich-02');
    expect(parseSceneRichBatchArgs(['--batch','scene-rich-03'])).toBe('scene-rich-03');
    for (const args of [['scene-rich-02'],['--batch'],['--batch','scene-rich-04'],['--batch','../scene-rich-01'],['--batch','scene-rich-02','extra'],['--output','/tmp']]) {
      expect(()=>parseSceneRichBatchArgs(args)).toThrow();
    }
  });
  it('rejects unsafe paths, duplicate words and invalid caption placement', () => {
    for (const patch of [{masterFile:'../secret.png'},{id:'../escape'},{captionPlacement:'middle'},{sourceType:'unknown'},{caption:'其他'}]) {
      const m=manifest(); Object.assign(m.items[0],patch); expect(()=>validateSceneRichManifest(m)).toThrow();
    }
    const m=manifest();m.items[1].keyword=m.items[0].keyword;expect(()=>validateSceneRichManifest(m)).toThrow();
  });
  it.each(['scene-rich-01', 'scene-rich-02', 'scene-rich-03'])('renders %s distinct full-scene poses, closed-loop GIF and first-frame thumbnail', async batchId => {
    const m = manifest();
    if (batchId === 'scene-rich-02') {
      m.batchId = batchId;
      m.items = ['你懂的', '细说', '什么事', '原来如此'].map((keyword, i) => ({ ...m.items[i], id: `scene-rich-02-${i}`, masterFile: `masters/scene-rich-02-${i}.png`, keyword, caption: keyword }));
    }
    if (batchId === 'scene-rich-03') {
      m.batchId = batchId;
      m.items = ['你先跑', '不高兴了', '你认真的', '被你发现了'].map((keyword, i) => ({ ...m.items[i], id: `scene-rich-03-${i}`, masterFile: `masters/scene-rich-03-${i}.png`, keyword, caption: keyword }));
    }
    const item=validateSceneRichManifest(m).items[0];
    const result=await renderSceneRichGif(await master(),item);
    expect(result.poses).toHaveLength(4);
    expect(result.audit.issues).toEqual([]);
    expect(result.audit.metadata).toMatchObject({width:240,height:240,pages:16,loop:0,durationMs:1600,loopClosed:true});
    // GIF 局部调色板可能产生 1 级 RGB 舍入差；闭环必须无感知差异。
    expect(result.audit.metadata.loopClosure.boundaryDifferenceRatio).toBe(0);
    expect(result.audit.metadata.motion.uniqueFrameCount).toBeGreaterThanOrEqual(4);
    expect((await sharp(result.thumbnail).metadata()).format).toBe('webp');
    expect(result.audit.metadata.frames[0].effectiveAlphaPixels).toBeGreaterThanOrEqual(236*236);
    expect(await sharp(result.thumbnail).ensureAlpha().raw().toBuffer()).toEqual(await sharp(result.gif,{page:0,pages:1}).ensureAlpha().raw().toBuffer());
    expect((await renderSceneRichGif(await master(),item)).gif).toEqual(result.gif);
  });
  it('rejects non-square and non-PNG masters before rendering', async () => {
    const item=validateSceneRichManifest(manifest()).items[0];
    await expect(renderSceneRichGif(await sharp(await master()).resize(512,500).png().toBuffer(),item)).rejects.toThrow(/母版/);
    await expect(renderSceneRichGif(await sharp(await master()).jpeg().toBuffer(),item)).rejects.toThrow(/母版/);
  });
  it('keeps all four real scene masters under 250KB without dropping animation gates', async () => {
    const source = new URL('../../../assets/expression/batches/scene-rich-01/', import.meta.url);
    const manifest = validateSceneRichManifest(JSON.parse(await readFile(new URL('manifest.json', source), 'utf8')));
    for (const item of manifest.items) {
      const result = await renderSceneRichGif(await readFile(new URL(item.masterFile, source)), item);
      expect(result.gif.length).toBeLessThan(250 * 1024);
      expect(result.audit.issues).toEqual([]);
      expect(result.audit.metadata.pages).toBe(16);
      expect(result.audit.metadata.motion.uniqueFrameCount).toBeGreaterThanOrEqual(4);
    }
  }, 20000);
  it('rejects fake four-pose static masters', async () => {
    await expect(renderSceneRichGif(await master(true),validateSceneRichManifest(manifest()).items[0])).rejects.toThrow(/审计/);
  });
});
