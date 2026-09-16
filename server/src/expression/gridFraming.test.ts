import {describe,it,expect} from 'vitest';
import sharp from 'sharp';
import {framePoseGrid} from './gridFraming.js';
async function fixture(){
 const poses=await Promise.all(Array.from({length:12},(_,i)=>sharp(Buffer.from(`<svg width="100" height="100"><rect width="100" height="100" fill="white"/><rect x="40" y="${60+i}" width="20" height="10" fill="black"/><rect x="${10+i}" y="20" width="4" height="4" fill="black"/></svg>`)).png().toBuffer()));
 return sharp({create:{width:400,height:300,channels:3,background:'white'}}).composite(poses.map((input,i)=>({input,left:i%4*100,top:Math.floor(i/4)*100}))).png().toBuffer();
}
describe('explicit uniform pose-grid framing',()=>{
 it('uses one scale, aligns foot references, preserves different poses and does not mutate original',async()=>{
  const source=await fixture(),copy=Buffer.from(source);
  const r=await framePoseGrid(source,{scale:0.8,targetBaselineRatio:0.69,baselines:Array.from({length:12},(_,i)=>70+i)});
  expect(source.equals(copy)).toBe(true);
  expect(await sharp(r.png).metadata()).toMatchObject({width:400,height:300,format:'png'});
  expect(new Set(r.transforms.map(t=>t.scale)).size).toBe(1);
  const tiles=[];
  for(let i=0;i<12;i++){
   const {data,info}=await sharp(r.png).extract({left:i%4*100,top:Math.floor(i/4)*100,width:100,height:100}).removeAlpha().raw().toBuffer({resolveWithObject:true});
   let bottom=-1;
   for(let y=0;y<100;y++)for(let x=0;x<100;x++)if(data[(y*100+x)*info.channels]<128)bottom=Math.max(bottom,y);
   expect(bottom).toBeGreaterThanOrEqual(67);expect(bottom).toBeLessThanOrEqual(70);
   tiles.push(data.toString('base64'));
  }
  expect(new Set(tiles).size).toBe(12);
 });
 it('rejects invalid settings and any placement that would crop a source tile',async()=>{
  const source=await fixture(),valid={scale:0.8,targetBaselineRatio:0.69,baselines:Array(12).fill(75)};
  for(const patch of [{scale:0},{scale:1.1},{scale:NaN},{targetBaselineRatio:0.9},{baselines:[75]},{baselines:Array(12).fill(NaN)},{baselines:Array(12).fill(101)},{baselines:Array(12).fill(10)},{baselines:Array(12).fill(99)}])
   await expect(framePoseGrid(source,{...valid,...patch})).rejects.toThrow();
  await expect(framePoseGrid(await sharp(source).resize(400,400).png().toBuffer(),valid)).rejects.toThrow();
  await expect(framePoseGrid(await sharp(source).jpeg().toBuffer(),valid)).rejects.toThrow();
 });
});
