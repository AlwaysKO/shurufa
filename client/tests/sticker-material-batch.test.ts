import { expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { runMaterialBatch, type BatchRow } from '../src/api/stickerMaterialBatch';
const sha = 'a'.repeat(64), other = 'b'.repeat(64);
const material = (hash = sha) => ({ sha256: hash, ids: [1], keywords: ['开心', '高兴'], assigned: true, url: '/a.gif', format: 'gif', width: 1, height: 1 });
const row = (name = 'a.gif'): BatchRow => ({ file: { name, size: 3, arrayBuffer: async () => new ArrayBuffer(3) } as File, status: 'pending' });
const setup = () => ({ hash: vi.fn(async () => sha), match: vi.fn(async (hashes: string[]) => ({ items: hashes.map(sha256 => ({ sha256, status: 'missing' })) })), upload: vi.fn(async () => ({ status: 'imported', material: material() })) });
it('先匹配实际后台，已有原图展示全部关键词且不上传', async () => {
 const api = setup(); api.match.mockResolvedValue({ items: [{ sha256: sha, status: 'existing', material: material() }]} as any);
 const rows = [row()]; await runMaterialBatch(rows, api, () => false);
 expect(api.upload).not.toHaveBeenCalled(); expect(rows[0].status).toBe('existing'); expect(rows[0].material?.keywords).toEqual(['开心','高兴']);
});
it('同批相同原图只传一次，保留重复行及结果', async () => {
 const api = setup(), rows = [row(), row('copy.gif')]; await runMaterialBatch(rows, api, () => false);
 expect(api.upload).toHaveBeenCalledTimes(1); expect(rows.map(r => r.status)).toEqual(['imported','duplicate']); expect(rows[1].material?.sha256).toBe(sha);
});
it('unavailable 不补传，单项失败后继续下一张', async () => {
 const api = setup(); api.hash.mockResolvedValueOnce(sha).mockResolvedValueOnce(other); api.match.mockResolvedValueOnce({ items: [{sha256: sha,status:'unavailable'}]}); api.upload.mockResolvedValue({status:'imported',material:material(other)});
 const rows = [row(),row('b.gif')]; await runMaterialBatch(rows,api,()=>false); expect(rows.map(r=>r.status)).toEqual(['failed','imported']); expect(api.upload).toHaveBeenCalledTimes(1);
});
it.each([[],[{sha256:other,status:'missing'}],[{sha256:sha,status:'missing'},{sha256:sha,status:'missing'}],[{sha256:sha,status:'unexpected'}],[{sha256:sha,status:'existing',material:material(other)}]])('拒绝不完整或错配 match 响应 %j',async items=>{
 const api=setup(); api.match.mockResolvedValue({items} as any); const rows=[row()]; await runMaterialBatch(rows,api,()=>false); expect(rows[0].status).toBe('failed'); expect(api.upload).not.toHaveBeenCalled();
});
it('上传响应丢失后回查确认已提交，不重复上传',async()=>{
 const api=setup(); api.upload.mockRejectedValue(Error('lost')); api.match.mockResolvedValueOnce({items:[{sha256:sha,status:'missing'}]}).mockResolvedValueOnce({items:[{sha256:sha,status:'existing',material:material()}]} as any);
 const rows=[row()]; await runMaterialBatch(rows,api,()=>false); expect(rows[0].status).toBe('existing'); expect(api.match).toHaveBeenCalledTimes(2); expect(api.upload).toHaveBeenCalledTimes(1);
});
it('失败重试重新匹配，而非直接重传',async()=>{
 const api=setup(); api.upload.mockRejectedValue(Error('lost')); const rows=[row()]; await runMaterialBatch(rows,api,()=>false); expect(rows[0].status).toBe('failed');
 api.match.mockResolvedValue({items:[{sha256:sha,status:'existing',material:material()}]} as any); await runMaterialBatch(rows,api,()=>false); expect(rows[0].status).toBe('existing'); expect(api.upload).toHaveBeenCalledTimes(1);
});
it('取消在当前请求结束后停止后续，不将取消冒充成功',async()=>{
 let stopped=false; const api=setup(); api.match.mockImplementation(async()=>{stopped=true;return {items:[{sha256:sha,status:'missing'}]}}); const rows=[row(),row()]; await runMaterialBatch(rows,api,()=>stopped); expect(api.upload).not.toHaveBeenCalled(); expect(rows.map(r=>r.status)).toEqual(['cancelled','cancelled']);
});
it('超大文件拒绝读取，继续处理合法文件',async()=>{
 const api=setup(), large=row(); Object.defineProperty(large.file,'size',{value:11*1024*1024}); const rows=[large,row()]; await runMaterialBatch(rows,api,()=>false); expect(rows[0].status).toBe('failed'); expect(api.hash).toHaveBeenCalledTimes(1);
});
it('后台缺图时上传原文件；新图未分配且没有伪造关键词',async()=>{
 const api=setup(); api.upload.mockResolvedValue({status:'imported',material:{...material(),keywords:[],assigned:false}});
 const rows=[row()]; await runMaterialBatch(rows,api,()=>false);
 expect(api.upload).toHaveBeenCalledWith(rows[0].file,sha); expect(rows[0].material?.assigned).toBe(false); expect(rows[0].material?.keywords).toEqual([]);
});
it('取消期间已提交的当前图保留真实成功，后续图停止',async()=>{
 let stopped=false; const api=setup(); api.upload.mockImplementation(async()=>{stopped=true;return {status:'imported',material:material()}});
 const rows=[row(),row()]; await runMaterialBatch(rows,api,()=>stopped); expect(rows.map(r=>r.status)).toEqual(['imported','cancelled']); expect(api.upload).toHaveBeenCalledTimes(1);
});
it('伪造上传成功响应不得当作成功，必须重新比对',async()=>{
 const api=setup(); api.upload.mockResolvedValue({status:'imported',material:material(other)});
 const rows=[row()]; await runMaterialBatch(rows,api,()=>false); expect(rows[0].status).toBe('failed'); expect(api.match).toHaveBeenCalledTimes(2);
});
