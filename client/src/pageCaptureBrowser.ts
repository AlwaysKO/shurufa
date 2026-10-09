export type PageCaptureKind = 'conversation_list' | 'payment' | 'media_feed' | 'image_post' | 'mini_app';
export interface PageCaptureRow {
  title?: string; note?: string;
  id: string;
  platform: 'wechat' | 'douyin';
  kind: PageCaptureKind;
  captured_at: string;
  received_at: string;
  width: number;
  height: number;
  sha256: string;
  mime_type: string;
}
export interface PageCaptureQuery {
  userId: string;
  page: number;
  platform: '' | 'wechat' | 'douyin';
  kind: '' | PageCaptureKind;
  from?:string; to?:string; q?:string;
}
export interface PageCaptureList { records: PageCaptureRow[]; total: number; page_size: number }
export const pageKindLabels: Record<PageCaptureKind, string> = {
  conversation_list: '会话列表', payment: '支付相关页面', media_feed: '信息流（未确认视频）', image_post: '图文', mini_app: '小程序',
};
export function pageCaptureListUrl(query: PageCaptureQuery): string {
  const params = new URLSearchParams({ user_id: query.userId, page: String(query.page) });
  if (query.platform) params.set('platform', query.platform);
  for(const key of ['from','to','q'] as const) if(query[key]) params.set(key,query[key]!);
  if (query.kind) params.set('kind', query.kind);
  return `/api/v1/dashboard/page-captures?${params}`;
}
export function pageCaptureImageUrl(id: string, userId: string): string {
  return `/api/v1/dashboard/page-captures/${encodeURIComponent(id)}/image?user_id=${encodeURIComponent(userId)}`;
}

/** 可独立验证的加载状态；无轮询。新手机/筛选/页码或卸载使旧请求失效。 */
export class PageCaptureBrowser {
  rows: PageCaptureRow[] = [];
  total = 0;
  loading = false;
  error = '';
  userId = '';
  selected: PageCaptureRow | null = null;
  failedImages: string[] = [];
  private generation = 0;
  constructor(private fetch: (query: PageCaptureQuery) => Promise<PageCaptureList>) {}
  invalidate(): void {
    this.generation++;
    this.rows = []; this.total = 0; this.loading = false; this.error = '';
    this.userId = ''; this.selected = null; this.failedImages = [];
  }
  async load(query: PageCaptureQuery): Promise<void> {
    this.invalidate();
    const generation = this.generation;
    const frozen = { ...query };
    this.userId = frozen.userId;
    if (!frozen.userId) return;
    this.loading = true;
    try {
      const result = await this.fetch(frozen);
      if (generation !== this.generation) return;
      if (!Array.isArray(result.records) || !Number.isSafeInteger(result.total) || result.total < 0 || result.page_size !== 20 || result.records.length > 20)
        throw Error('页面记录响应格式异常，请重试');
      this.rows = result.records; this.total = result.total;
    } catch (error) {
      if (generation === this.generation) this.error = error instanceof Error ? error.message : '页面记录加载失败';
    } finally { if (generation === this.generation) this.loading = false; }
  }
  select(row: PageCaptureRow): void { this.selected = this.rows.includes(row) ? row : null; }
  imageFailed(row: PageCaptureRow): void {
    if (this.rows.includes(row) && !this.failedImages.includes(row.id)) this.failedImages = [...this.failedImages, row.id];
  }
  retryImage(row: PageCaptureRow): void { this.failedImages = this.failedImages.filter(id => id !== row.id); }
}
