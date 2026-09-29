import { Router, raw } from 'express';
import { unlink } from 'node:fs/promises';
import type pg from 'pg';
import { importMaterial, listMaterials, matchMaterials, updateMaterialKeywords } from '../stickers/materials.js';
import { StickerGroupError, withGroupLock } from './stickerLibrary.js';
import { SHARED_STICKER_OWNER } from '../stickers/shared.js';
import { publishStickerBundle } from '../stickers/bundle.js';
export function createStickerMaterialsRouter(pool:pg.Pool):Router {
  const router=Router();
  router.get('/sticker-materials',async(req,res,next)=>{try{res.json(await listMaterials(pool,req.query));}catch(error){next(error);}});
  router.post('/sticker-materials/match',async(req,res,next)=>{try{res.json(await matchMaterials(pool,req.body?.sha256s));}catch(error){next(error);}});
  router.post('/sticker-materials',raw({type:'application/octet-stream',limit:'10mb'}),async(req,res,next)=>{
    const createdFiles:string[]=[];
    try {
      let result;
      try { result=await withGroupLock(pool,SHARED_STICKER_OWNER,db=>importMaterial(db,{buffer:req.body,filename:req.query.filename,sha256:req.query.sha256},createdFiles)); }
      catch(error) { await Promise.all(createdFiles.map(path=>unlink(path))); throw error; }
      if(result.status==='imported') await publishStickerBundle(pool);
      res.status(result.status==='imported'?201:200).json(result);
    }catch(error){next(error);}
  });
  router.patch('/sticker-materials/:sha256/keywords',async(req,res,next)=>{
    try {const material=await withGroupLock(pool,SHARED_STICKER_OWNER,db=>updateMaterialKeywords(db,req.params.sha256,req.body)); await publishStickerBundle(pool); res.json({material});}catch(error){next(error);}
  });
  router.use((error:unknown,_req:import('express').Request,res:import('express').Response,next:import('express').NextFunction)=>{
    if(error instanceof StickerGroupError) {res.status(error.status).json({error:error.message});return;}
    if((error as {type?:string})?.type==='entity.too.large'){res.status(413).json({error:'图片超过10MB限制'});return;} next(error);
  });
  return router;
}
