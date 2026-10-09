import { dashboardFetch } from '../auth';
import { videoVisitListUrl, type VideoVisitList, type VideoVisitQuery } from '../videoVisitBrowser';
export async function fetchVideoVisits(query: VideoVisitQuery): Promise<VideoVisitList> {
  const response = await dashboardFetch(videoVisitListUrl(query));
  if (!response.ok) throw new Error(`停留记录加载失败（${response.status}）`);
  return response.json();
}
