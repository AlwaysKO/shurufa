import { dashboardFetch } from '../auth';
import { pageCaptureListUrl, type PageCaptureList, type PageCaptureQuery } from '../pageCaptureBrowser';

/** URL使用调用时冻结的手机ID，不在异步完成时改取全局当前手机。 */
export async function fetchPageCaptures(query: PageCaptureQuery): Promise<PageCaptureList> {
  const response = await dashboardFetch(pageCaptureListUrl(query));
  if (!response.ok) throw new Error(`页面记录加载失败（${response.status}）`);
  return response.json();
}
