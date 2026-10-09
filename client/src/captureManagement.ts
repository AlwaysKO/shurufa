export interface ManagedCapture { id:string; title?:string; note?:string }
export interface CaptureScope { userId:string; page:number; platform?:string; kind?:string; from?:string; to?:string; q?:string; observation_kind?:string; complete?:string; exit_reason?:string }
export type CaptureMutation = (path:string,userId:string,body:unknown,method?:'POST'|'PATCH')=>Promise<any>;
/** 确认与回调绑定手机、筛选及页码；切换范围后不再发下一批或修改新列表。 */
export class CaptureManagement {
 selectedIds:string[]=[];pendingDelete:string[]=[];editor:{id:string;title:string;note:string}|null=null;cleanup:{token:string;total:number;cutoff:string;offset:number;deleted:number;skipped:number}|null=null;busy=false;error='';message='';
 private stopRequested=false;
 private scope:CaptureScope={userId:'',page:1};private key='';private generation=0;private rows:ManagedCapture[]=[];
 constructor(private mutate:CaptureMutation){}
 setScope(scope:CaptureScope):void{const key=JSON.stringify(scope);if(key===this.key)return;this.invalidate();this.scope={...scope};this.key=key;}
 invalidate():void{this.generation++;this.key='';this.scope={userId:'',page:1};this.rows=[];this.selectedIds=[];this.pendingDelete=[];this.editor=null;this.cleanup=null;this.busy=false;this.error='';this.message='';}
 setRows(rows:ManagedCapture[]):void{this.rows=[...rows];this.selectedIds=this.selectedIds.filter(id=>rows.some(row=>row.id===id));}
 toggle(id:string):void{if(this.busy||!this.rows.some(row=>row.id===id))return;this.selectedIds=this.selectedIds.includes(id)?this.selectedIds.filter(v=>v!==id):[...this.selectedIds,id];}
 selectAll():void{if(!this.busy)this.selectedIds=this.rows.map(row=>row.id);}
 cancelSelection():void{if(!this.busy)this.selectedIds=[];}
 requestDelete(id?:string):void{if(this.busy)return;this.pendingDelete=(id?[id]:this.selectedIds).filter(v=>this.rows.some(row=>row.id===v));this.cleanup=null;this.editor=null;this.error='';this.message='';}
 edit(row:ManagedCapture):void{if(this.busy||!this.rows.some(r=>r.id===row.id))return;this.editor={id:row.id,title:row.title||'',note:row.note||''};this.pendingDelete=[];this.cleanup=null;this.error='';}
 private async run(task:(userId:string)=>Promise<boolean>):Promise<boolean>{if(this.busy||!this.scope.userId)return false;const generation=this.generation,userId=this.scope.userId;this.busy=true;this.error='';
  try{return await task(userId)&&generation===this.generation;}catch(error){if(generation===this.generation)this.error=error instanceof Error?error.message:'操作失败，请重试';return false;}finally{if(generation===this.generation)this.busy=false;}}
 async confirmDelete():Promise<boolean>{if(!this.pendingDelete.length)return false;const generation=this.generation,ids=[...this.pendingDelete];return this.run(async userId=>{const result=await this.mutate('delete',userId,{ids,confirm:'DELETE'});if(generation!==this.generation)return false;if(!Number.isSafeInteger(result.deleted)||result.deleted<0)throw Error('删除响应异常，请刷新核对');this.pendingDelete=[];this.selectedIds=[];this.message=`已删除 ${result.deleted} 条记录`;return true;});}
 async saveEdit():Promise<boolean>{if(!this.editor)return false;const generation=this.generation,editor={...this.editor};return this.run(async userId=>{const result=await this.mutate(encodeURIComponent(editor.id),userId,{title:editor.title,note:editor.note},'PATCH');if(generation!==this.generation)return false;if(result.ok!==true)throw Error('保存响应异常，请刷新核对');this.editor=null;this.message='名称和备注已保存';return true;});}
 async previewCleanup(days:1|7|30):Promise<void>{const generation=this.generation;const {userId:_user,page:_page,...filters}=this.scope;this.cleanup=null;this.pendingDelete=[];this.editor=null;await this.run(async userId=>{const result=await this.mutate('cleanup/preview',userId,{days,...Object.fromEntries(Object.entries(filters).filter(([,value])=>value!==''&&value!==undefined))});if(generation!==this.generation)return false;if(typeof result.token!=='string'||!result.token||!Number.isSafeInteger(result.total)||result.total<0||typeof result.cutoff!=='string')throw Error('清理预览响应异常，请重试');this.cleanup={...result,offset:0,deleted:0,skipped:0};this.message='';return false;});}
 stopCleanup():void{this.stopRequested=true;}
 async confirmCleanup():Promise<boolean>{
  if(this.busy||!this.cleanup||this.cleanup.total===0)return false;
  const generation=this.generation;this.stopRequested=false;
  return this.run(async userId=>{
   while(this.cleanup&&generation===this.generation){
    const preview={...this.cleanup};
    const result=await this.mutate('cleanup/batch',userId,{token:preview.token,offset:preview.offset,confirm:'DELETE'});
    if(generation!==this.generation)return false;
    if(!Number.isSafeInteger(result.processed)||result.processed<=preview.offset||result.processed>preview.total||result.total!==preview.total||!Number.isSafeInteger(result.deleted)||result.deleted<0||!Number.isSafeInteger(result.skipped)||result.skipped<0||typeof result.done!=='boolean')throw Error('清理进度异常，请重新预览核对');
    this.cleanup={...preview,offset:result.processed,deleted:result.deleted,skipped:result.skipped};
    this.message=`已处理 ${result.processed} / ${result.total} 条，删除 ${result.deleted} 条，跳过 ${result.skipped} 条`;
    if(result.done){this.cleanup=null;this.selectedIds=[];return true;}
    if(this.stopRequested){this.message+='；已停止后续清理';return true;}
   }
   return false;
  });
 }
}
