import type pg from 'pg';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, realpath, open } from 'node:fs/promises';
import { basename, extname, join, sep } from 'node:path';
import sharp, { type Metadata } from 'sharp';
import { normalizeRecommendationPhrase } from '../expression/recommendationGroups.js';
import { assertStickerKeywordsActive, loadStickerLibrary, rememberStickerKeywords, splitStickerKeywords, stickerGroupKeyword, StickerGroupError } from '../api/stickerLibrary.js';
import { SHARED_STICKER_OWNER as OWNER } from './shared.js';
type Database = Pick<pg.Pool, 'query'>;
type Row = { id: number; keywords: string; file_name: string; format: string; width: number|null; height: number|null; sha256: string|null };
export interface Material { sha256: string; ids: number[]; keywords: string[]; url: string; format: string; width: number|null; height: number|null; assigned: boolean }
export function validateMaterialSha(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) throw new StickerGroupError(400,'invalid sha256');
  return value;
}
async function readMaterials(db: Database, root: string, hashes?:string[]) {
  const filter=hashes ? " WHERE sha256 = ANY($1::text[]) OR sha256 IS NULL OR sha256 = ''" : '';
  const rows=(await db.query<Row>(`SELECT id, keywords, file_name, format, width, height, sha256 FROM sticker${filter} ORDER BY id`,hashes?[hashes]:[])).rows;
  const groups=new Map<string,{material:Material;rows:Row[];assignmentId:number}>(), warnings:string[]=[];
  const unavailable=new Set<string>(), missing:Row[]=[];
  const directory=join(root,'uploads/stickers');
  for(const row of rows) {
    let bytes:Buffer;
    try {
      if(!row.file_name || basename(row.file_name)!==row.file_name || /[\\\x00]/.test(row.file_name)) throw Error('unsafe path');
      const [base,path]=await Promise.all([realpath(directory),realpath(join(directory,row.file_name))]);
      if(!path.startsWith(base+sep)) throw Error('unsafe path');
      bytes=await readFile(path);
    } catch(error) {
      warnings.push(`素材记录 ${row.id} 原图缺失或路径不安全`);
      if(row.sha256 && /^[a-f0-9]{64}$/.test(row.sha256)) {
        unavailable.add(row.sha256);
        if((error as NodeJS.ErrnoException).code==='ENOENT') missing.push(row);
      }
      continue;
    }
    const sha=createHash('sha256').update(bytes).digest('hex');
    if(row.sha256 && row.sha256!==sha) { warnings.push(`素材记录 ${row.id} 原图哈希不符`); unavailable.add(row.sha256); continue; }
    if(!row.sha256) await db.query('UPDATE sticker SET sha256=$1 WHERE id=$2',[sha,row.id]);
    let group=groups.get(sha);
    if(!group) { group={material:{sha256:sha,ids:[],keywords:[],url:`/uploads/stickers/${encodeURIComponent(row.file_name)}`,format:row.format,width:row.width??null,height:row.height??null,assigned:false},rows:[],assignmentId:Number(row.id)}; groups.set(sha,group); }
    group.rows.push(row);
  }
  for(const row of missing) {
    const group=groups.get(row.sha256!);
    if(group) group.rows.push(row);
  }
  for(const group of groups.values()) {
    group.rows.sort((a,b)=>Number(a.id)-Number(b.id));
    group.material.ids=group.rows.map(row=>Number(row.id));
    group.material.keywords=[...new Set(group.rows.flatMap(row=>splitStickerKeywords(row.keywords)))];
    group.material.assigned=group.material.keywords.length>0;
  }
  return {groups,warnings,unavailable};
}
export async function listMaterials(db: Database, query: {state?:unknown;q?:unknown;page?:unknown;page_size?:unknown}={}, root=process.cwd()) {
  const state=query.state??'all', page=Number(query.page??1), pageSize=Number(query.page_size??30);
  if(!['all','assigned','unassigned'].includes(String(state)) || !Number.isInteger(page)||page<1 || !Number.isInteger(pageSize)||pageSize<1||pageSize>100 || (query.q!==undefined&&typeof query.q!=='string')) throw new StickerGroupError(400,'invalid material filter');
  const {groups,warnings}=await readMaterials(db,root), q=String(query.q??'').trim().toLowerCase();
  // 同 SHA 聚合以最早记录代表首次入库；重复上传和编辑关联不提升素材顺序。
  const items=[...groups.values()].map(g=>g.material).sort((a,b)=>b.ids[0]-a.ids[0]).filter(m=>(state==='all'||m.assigned===(state==='assigned'))&&(!q||m.keywords.some(k=>k.toLowerCase().includes(q))));
  return {items:items.slice((page-1)*pageSize,page*pageSize),total:items.length,page,pageSize,warnings};
}
export async function matchMaterials(db: Database, sha256s: unknown, root=process.cwd()) {
  if(!Array.isArray(sha256s)||sha256s.length>500) throw new StickerGroupError(400,'最多匹配500张素材');
  const hashes=sha256s.map(validateMaterialSha);
  if(!hashes.length) return {items:[],warnings:[]};
  const {groups,warnings,unavailable}=await readMaterials(db,root,hashes);
  return {items:hashes.map(sha256=>{const material=groups.get(sha256)?.material; return material?{sha256,status:'existing' as const,material}:{sha256,status:unavailable.has(sha256)?'unavailable' as const:'missing' as const};}),warnings};
}
// libvips 对部分 GIF 截断容错；补验块长度与结束符，不重编码原图。
function assertCompleteGif(bytes: Buffer): void {
  let offset=13, frames=0;
  const take=(count:number)=>{ if(offset+count>bytes.length) throw Error('truncated GIF'); offset+=count; };
  if(bytes.length<13) throw Error('truncated GIF');
  if(bytes[10]&0x80) take(3*(2**((bytes[10]&7)+1)));
  const subBlocks=()=>{for(;;){take(1);const count=bytes[offset-1];if(!count)return;take(count);}};
  while(offset<bytes.length) {
    const marker=bytes[offset++];
    if(marker===0x3b) {if(!frames) throw Error('empty GIF');return;}
    if(marker===0x21) {take(1);subBlocks();continue;}
    if(marker!==0x2c) throw Error('invalid GIF block');
    take(9); const packed=bytes[offset-1];
    if(packed&0x80) take(3*(2**((packed&7)+1)));
    take(1);subBlocks();frames++;
  }
  throw Error('missing GIF trailer');
}
/** 必须由调用方在公共图库锁保护的事务中调用；回滚时仅清理 createdFiles，提交后不得清理。 */
export async function importMaterial(db: Database, input:{buffer:Buffer;filename:unknown;sha256?:unknown}, createdFiles:string[], root=process.cwd()) {
  const {buffer,filename}=input;
  if(typeof filename!=='string'||!filename||basename(filename)!==filename||/[\\\x00]/.test(filename)) throw new StickerGroupError(400,'invalid filename');
  if(!Buffer.isBuffer(buffer)||!buffer.length||buffer.length>10*1024*1024) throw new StickerGroupError(400,'file size must be 0 ~ 10MB');
  const sha=createHash('sha256').update(buffer).digest('hex');
  if(input.sha256!==undefined&&validateMaterialSha(input.sha256)!==sha) throw new StickerGroupError(400,'image sha256 mismatch');
  const removed = await db.query('SELECT asset_id FROM keyword_gif_removal WHERE user_id=$1 AND sha256=$2', [OWNER, sha]);
  if (removed.rows.some(row => row.asset_id === `material:${sha}`)) throw new StickerGroupError(409, '该素材已删除，不可重复导入');
  const formats:Record<string,string>={'.gif':'gif','.png':'png','.jpg':'jpg','.jpeg':'jpg','.webp':'webp'},format=formats[extname(filename).toLowerCase()];
  let metadata:Metadata;
  try {
    const image=sharp(buffer,{animated:true,limitInputPixels:100_000_000,failOn:'warning'});
    metadata=await image.metadata();
    if(metadata.format==='gif') assertCompleteGif(buffer);
    await image.stats(); // 强制完整像素解码，仅校验，不保存解码产物。
  }
  catch { throw new StickerGroupError(400,'invalid image'); }
  if(!format||(metadata.format==='jpeg'?'jpg':metadata.format)!==format) throw new StickerGroupError(400,'image format does not match filename');
  const existing=(await matchMaterials(db,[sha],root)).items[0];
  if(existing.status==='unavailable') throw new StickerGroupError(409,'已有素材原图缺失或哈希异常，请先修复');
  if(existing.status==='existing') return {status:'existing' as const,material:existing.material};
  const directory=join(root,'uploads/stickers'); await mkdir(directory,{recursive:true});
  const filenameStored=`${randomUUID()}.${format}`, path=join(directory,filenameStored);
  const file=await open(path,'wx');
  createdFiles.push(path); // wx成功才拥有该路径；写入中途失败也必须补偿清理。
  try {await file.writeFile(buffer);} finally {await file.close();}
  const result=await db.query<Row>('INSERT INTO sticker(user_id,keywords,file_name,format,width,height,sha256) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id',[OWNER,'',filenameStored,format,metadata.width??null,metadata.pageHeight??metadata.height??null,sha]);
  return {status:'imported' as const,material:{sha256:sha,ids:[Number(result.rows[0].id)],keywords:[],url:`/uploads/stickers/${filenameStored}`,format,width:metadata.width??null,height:metadata.pageHeight??metadata.height??null,assigned:false}};
}
/** 差量关联；调用方须持公共图库事务锁，提交后归档。 */
export async function updateMaterialKeywords(db: Database, sha256: string, input:unknown, root=process.cwd()) {
  validateMaterialSha(sha256);
  const change=input as {add?:unknown;remove?:unknown;requireExistingGroups?:unknown};
  if(change?.requireExistingGroups!==undefined && typeof change.requireExistingGroups!=='boolean') throw new StickerGroupError(400,'invalid group selection mode');
  function words(value:unknown) { if(!Array.isArray(value)||value.length>500) throw new StickerGroupError(400,'invalid keyword delta'); return value.map(word=>{ if(typeof word!=='string'||word.length>100||/[,，\r\n]/.test(word)||!normalizeRecommendationPhrase(word)) throw new StickerGroupError(400,'invalid keyword'); return normalizeRecommendationPhrase(word); }); }
  const add=words(change?.add),remove=words(change?.remove);
  const library=await loadStickerLibrary(db,OWNER,root);
  const canonical=(word:string)=>library.groups.find(g=>[g.keyword,...g.aliases].some(a=>normalizeRecommendationPhrase(a)===normalizeRecommendationPhrase(word)))?.keyword??stickerGroupKeyword(word);
  const additions=[...new Set(add.map(canonical))],removals=new Set(remove.map(canonical));
  if(change.requireExistingGroups===true && additions.some(word=>!library.groups.some(g=>g.keyword===word))) throw new StickerGroupError(409,'关键词组已不存在，请刷新候选后重新选择');
  if(additions.some(w=>removals.has(w))) throw new StickerGroupError(400,'同一关键词不能同时添加和移除');
  await assertStickerKeywordsActive(db,additions.join(','));
  const group=(await readMaterials(db,root)).groups.get(sha256);
  if(!group) throw new StickerGroupError(404,'素材不存在或原图不可用');
  const retained=group.rows.map(row=>splitStickerKeywords(row.keywords).filter(word=>!removals.has(canonical(word))));
  const present=new Set(retained.flat().map(canonical));
  const assignmentIndex=group.rows.findIndex(row=>Number(row.id)===group.assignmentId);
  retained[assignmentIndex].push(...additions.filter(word=>!present.has(word)));
  for(let i=0;i<group.rows.length;i++) {
    const row=group.rows[i],keywords=retained[i].join(',');
    await rememberStickerKeywords(db,OWNER,`${row.keywords},${keywords}`);
    if(keywords!==row.keywords) await db.query('UPDATE sticker SET keywords=$1 WHERE id=$2',[keywords,row.id]);
  }
  return (await readMaterials(db,root)).groups.get(sha256)!.material;
}

/** 调用方持公共图库事务锁；只删除图库记录，保留原文件及制作归档。 */
export async function deleteMaterials(db: Database, input: unknown, root = process.cwd()) {
  const body = input as { confirm?: unknown; sha256s?: unknown } | null;
  if (body?.confirm !== 'DELETE' || !Array.isArray(body.sha256s) || !body.sha256s.length || body.sha256s.length > 30) {
    throw new StickerGroupError(400, '请确认删除，并选择当前页的 1～30 张素材');
  }
  const hashes = [...new Set(body.sha256s.map(validateMaterialSha))];
  const { groups } = await readMaterials(db, root, hashes);
  if (hashes.some(sha => !groups.has(sha))) throw new StickerGroupError(409, '所选素材已变化或不可用，请刷新后重新选择');
  for (const sha of hashes) {
    const group = groups.get(sha)!;
    await rememberStickerKeywords(db, OWNER, group.material.keywords.join(','));
    await db.query(`INSERT INTO keyword_gif_removal(user_id,sha256,asset_id) VALUES($1,$2,$3)
      ON CONFLICT(user_id,sha256) DO UPDATE SET asset_id=EXCLUDED.asset_id`, [OWNER, sha, `material:${sha}`]);
    await db.query('DELETE FROM sticker WHERE sha256=$1', [sha]);
  }
  return { deleted: hashes.length };
}
