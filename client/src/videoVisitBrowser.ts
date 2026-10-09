export type VideoExitReason = 'page_changed' | 'switched' | 'exit' | 'background' | 'locked' | 'interrupted';
export interface VideoVisitRow {
  observation_kind: 'confirmed_video' | 'unconfirmed_feed';
  id: string; platform: 'wechat' | 'douyin'; entered_at: number; ended_at: number | null;
  duration_ms: number | null; exit_reason: VideoExitReason; complete: boolean;
  first_image_id: string | null; last_image_id: string | null; received_at: string;
}
export interface VideoVisitQuery { userId: string; page: number; platform: '' | 'wechat' | 'douyin' }
export interface VideoVisitList { records: VideoVisitRow[]; total: number; page: number; page_size: number }
export const exitReasonLabels: Record<VideoExitReason,string> = { page_changed:'页面变化',switched:'切换视频',exit:'退出页面',background:'切换应用或退出应用',locked:'熄屏或锁屏',interrupted:'异常中断' };
export function observationKindLabel(value: unknown): string {
  return value === 'confirmed_video' ? '已确认视频' : value === 'unconfirmed_feed' ? '信息流页面停留（未确认单条视频）' : '观察类型未知';
}
export function durationLabel(value: number | null): string { return value === null ? '未知（记录不完整）' : `${value / 1000} 秒`; }
export function videoVisitListUrl(query: VideoVisitQuery): string {
  const params = new URLSearchParams({user_id:query.userId,page:String(query.page)});
  if(query.platform) params.set('platform',query.platform);
  return `/api/v1/dashboard/video-visits?${params}`;
}
/** 每次请求冻结手机与筛选；无轮询。原图事件绑定所属行和图片引用。 */
export class VideoVisitBrowser {
  rows: VideoVisitRow[]=[]; total=0; loading=false; error=''; userId=''; failedImages:string[]=[];
  selected: {row:VideoVisitRow; imageId:string; side:'first'|'last'} | null=null;
  private generation=0;
  constructor(private fetch:(query:VideoVisitQuery)=>Promise<VideoVisitList>){}
  invalidate():void { this.generation++; this.rows=[];this.total=0;this.loading=false;this.error='';this.userId='';this.selected=null;this.failedImages=[]; }
  async load(query:VideoVisitQuery):Promise<void>{
    this.invalidate();const generation=this.generation,frozen={...query};this.userId=frozen.userId;
    if(!frozen.userId)return;this.loading=true;
    try {const result=await this.fetch(frozen);if(generation!==this.generation)return;
      if(!Array.isArray(result.records)||!Number.isSafeInteger(result.total)||result.total<0||result.page_size!==20||result.page!==frozen.page||result.records.length>20)throw Error('停留记录响应格式异常，请重试');
      this.rows=result.records;this.total=result.total;
    }catch(error){if(generation===this.generation)this.error=error instanceof Error?error.message:'停留记录加载失败';}
    finally{if(generation===this.generation)this.loading=false;}
  }
  select(row:VideoVisitRow,side:'first'|'last'):void { const imageId=side==='first'?row.first_image_id:row.last_image_id;this.selected=this.rows.includes(row)&&imageId?{row,imageId,side}:null; }
  imageFailed(row:VideoVisitRow,imageId:string):void {const key=`${row.id}:${imageId}`;if(this.rows.includes(row)&&[row.first_image_id,row.last_image_id].includes(imageId)&&!this.failedImages.includes(key))this.failedImages=[...this.failedImages,key];}
  retryImage(row:VideoVisitRow,imageId:string):void{if(this.rows.includes(row))this.failedImages=this.failedImages.filter(key=>key!==`${row.id}:${imageId}`);}
}
