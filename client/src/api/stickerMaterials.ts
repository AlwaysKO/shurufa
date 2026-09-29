import { dashboardFetch } from '../auth';
export type Material = {
  sha256: string;
  ids: number[];
  keywords: string[];
  url: string;
  format: string;
  width: number | null;
  height: number | null;
  assigned: boolean;
};
export type MaterialQuery = {
  state: 'all' | 'assigned' | 'unassigned';
  q: string;
  page: number;
  page_size: number;
};
export type ImportAgent = {
  id: string;
  name: string;
  createdAt: string;
  lastSeenAt: string | null;
  revokedAt: string | null;
};
export type ImportJob = {
  id: string;
  agentId: string;
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'expired';
  createdAt: string;
  discovered: number;
  validated: number;
  validationFailed: number;
  counts: { existing: number; missing: number; imported: number; failed: number };
  sourceErrors: Record<string, number>;
  errors: { code: string; count: number }[];
  jobErrorCode: string | null;
};
const errors: Record<string, string> = {
  agent_limit: '助手数量已达上限（包含已撤销助手）；请联系管理员处理配对额度，不要反复配对。',
  pairing_limit: '配对请求过多，请等待已有配对码到期后重试。',
  job_limit: '任务数量已达上限，请等待或取消现有任务后重试。',
  rate_limited: '请求过于频繁，请稍后再试。',
  agent_not_found: '助手已撤销或不存在，请刷新助手列表并重新选择。',
  job_not_found: '任务不存在，请刷新任务列表。',
  unauthorized: '登录已过期，请重新登录后台。',
  import_internal_error: '导入服务暂时异常，请稍后刷新核对任务状态。',
};
async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await dashboardFetch(`/api/v1/dashboard/${path}`, {
    method,
    ...(body === undefined
      ? {}
      : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => null);
    const message = typeof data?.error === 'string' ? data.error : '';
    throw new Error(
      errors[message] ||
        (/^[a-z_]+$/.test(message) ? `操作未完成，请刷新后重试（${response.status}）` : message) ||
        `请求失败（${response.status}）`,
    );
  }
  return response.json();
}
const prefix = 'sticker-import';
export const stickerMaterials = {
  list: (query: MaterialQuery) =>
    request<{
      items: Material[];
      total: number;
      page: number;
      pageSize: number;
      warnings: string[];
    }>(
      `sticker-materials?${new URLSearchParams(Object.entries(query).map(([k, v]) => [k, String(v)]))}`,
    ),
  keywords: (sha: string, delta: { add: string[]; remove: string[] }) =>
    request<{ material: Material }>(
      `sticker-materials/${encodeURIComponent(sha)}/keywords`,
      'PATCH',
      delta,
    ),
  pair: () => request<{ code: string; expiresAt: string }>(`${prefix}/pairings`, 'POST'),
  agents: () => request<{ agents: ImportAgent[] }>(`${prefix}/agents`),
  revoke: (id: string) =>
    request<{ ok: boolean }>(`${prefix}/agents/${encodeURIComponent(id)}/revoke`, 'POST'),
  jobs: () => request<{ jobs: ImportJob[] }>(`${prefix}/jobs`),
  job: (id: string) => request<{ job: ImportJob }>(`${prefix}/jobs/${encodeURIComponent(id)}`),
  start: (agentId: string) => request<{ job: ImportJob }>(`${prefix}/jobs`, 'POST', { agentId }),
  cancel: (id: string) =>
    request<{ job: ImportJob }>(`${prefix}/jobs/${encodeURIComponent(id)}/cancel`, 'POST'),
};
