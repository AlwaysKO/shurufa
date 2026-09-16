import sharp from 'sharp';

/** Explicit uniform framing: no redraw, pose synthesis or per-pose scaling. */
export async function framePoseGrid(source: Buffer, options: {
  scale: number;
  targetBaselineRatio: number;
  /** Foot-reference y coordinates within each original tile, in pixels. */
  baselines: number[];
}) {
  const meta=await sharp(source).metadata();
  const width=meta.width ?? 0, height=meta.height ?? 0;
  if(meta.format!=='png'||!width||width%4!==0||height!==width/4*3|| (meta.pages ?? 1)!==1)
    throw new Error('构图整理只接受静态4列3行正方格PNG');
  const size=width/4;
  if(!Number.isFinite(options.scale)||options.scale<=0||options.scale>1
    ||!Number.isFinite(options.targetBaselineRatio)||options.targetBaselineRatio<=0||options.targetBaselineRatio>0.74
    ||!Array.isArray(options.baselines)||options.baselines.length!==12
    ||options.baselines.some(y=>!Number.isFinite(y)||y<0||y>size))
    throw new Error('缩放、目标基线或12格脚底参考无效');
  const scaledSize=Math.round(size*options.scale);
  if(scaledSize<1)throw new Error('缩放尺寸过小');
  const scale=scaledSize/size;
  const left=Math.floor((size-scaledSize)/2);
  const transforms=options.baselines.map((baseline,pose)=>({
    pose,scale,left,top:Math.round(size*options.targetBaselineRatio-baseline*scale),baseline,
  }));
  if(transforms.some(t=>t.top<0||t.top+scaledSize>size))
    throw new Error('该参考基线会裁切原格，请调整统一尺度或目标基线');
  const tiles=await Promise.all(transforms.map(async t=>({
    input:await sharp(source).extract({left:t.pose%4*size,top:Math.floor(t.pose/4)*size,width:size,height:size})
      .flatten({background:'white'}).resize(scaledSize,scaledSize).png().toBuffer(),
    left:t.pose%4*size+t.left,top:Math.floor(t.pose/4)*size+t.top,
  })));
  const png=await sharp({create:{width,height,channels:3,background:'white'}}).composite(tiles).png().toBuffer();
  return {png,transforms,cellSize:size,scaledSize,targetBaselineRatio:options.targetBaselineRatio};
}
