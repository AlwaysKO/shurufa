import type { Material } from './stickerMaterials';
export type BatchStatus = 'pending' | 'hashing' | 'matching' | 'uploading' | 'existing' | 'imported' | 'duplicate' | 'failed' | 'cancelled';
export type BatchRow = { file: File; status: BatchStatus; sha256?: string; material?: Material; error?: string };
type BatchApi = { hash?: (file: File) => Promise<string>; match: (hashes: string[]) => Promise<unknown>; upload: (file: File, sha: string) => Promise<unknown> };
const successful = new Set<BatchStatus>(['existing', 'imported', 'duplicate']);
function checkedMaterial(value: any, sha: string): Material {
  if (!value || value.sha256 !== sha || !Array.isArray(value.keywords) || !value.keywords.every((k: unknown) => typeof k === 'string') || !Array.isArray(value.ids) || typeof value.url !== 'string' || typeof value.assigned !== 'boolean') throw Error('后台素材响应不完整，请刷新后重试');
  return value;
}
async function match(api: BatchApi, sha: string) {
  const result: any = await api.match([sha]);
  if (!Array.isArray(result?.items) || result.items.length !== 1 || result.items[0]?.sha256 !== sha || !['existing','missing','unavailable'].includes(result.items[0]?.status)) throw Error('后台比对响应不完整，未上传，请重试');
  const item = result.items[0];
  return { status: item.status as string, material: item.status === 'existing' ? checkedMaterial(item.material, sha) : undefined };
}
export async function hashMaterial(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) throw Error('计算原图指纹需要 HTTPS 或 localhost 安全页面');
  const bytes = await file.arrayBuffer();
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(v => v.toString(16).padStart(2, '0')).join('');
}
/** 串行限定内存与请求量；取消不假定已发送的上传被回滚，重试始终先回查。 */
export async function runMaterialBatch(rows: BatchRow[], api: BatchApi, stopped: () => boolean): Promise<void> {
  const seen = new Map<string, Material>();
  for (const row of rows) {
    if (successful.has(row.status)) continue;
    if (stopped()) { row.status = 'cancelled'; continue; }
    row.error = undefined;
    try {
      if (!row.file.size || row.file.size > 10 * 1024 * 1024) throw Error('单张原图须大于 0 且不超过 10 MB');
      row.status = 'hashing';
      const sha = await (api.hash || hashMaterial)(row.file);
      if (!/^[a-f0-9]{64}$/.test(sha)) throw Error('原图指纹无效');
      row.sha256 = sha;
      if (stopped()) { row.status = 'cancelled'; continue; }
      const previous = seen.get(sha);
      if (previous) { row.material = previous; row.status = 'duplicate'; continue; }
      row.status = 'matching';
      const found = await match(api, sha);
      if (stopped()) { row.status = 'cancelled'; continue; }
      if (found.status === 'unavailable') throw Error('后台已有记录但原图缺失或不符，请先修复，未重复上传');
      if (found.status === 'existing') {
        row.material = found.material; row.status = 'existing';
      } else {
        row.status = 'uploading';
        try {
          const result: any = await api.upload(row.file, sha);
          if (!['existing','imported'].includes(result?.status)) throw Error('上传响应不完整');
          row.material = checkedMaterial(result.material, sha); row.status = result.status;
        } catch {
          // 请求可能已经提交；不能根据连接中断直接重传。
          if (stopped()) { row.status = 'cancelled'; row.error = '上传结果待确认，重新选择或重试时先比对后台'; continue; }
          const recovered = await match(api, sha);
          if (recovered.status !== 'existing') throw Error(recovered.status === 'unavailable' ? '后台原图异常，请先修复' : '上传未确认成功，请重试（会先重新比对后台）');
          row.material = recovered.material; row.status = 'existing';
        }
      }
      if (row.material) seen.set(sha, row.material);
    } catch (error) {
      row.status = stopped() ? 'cancelled' : 'failed';
      row.error = error instanceof Error ? error.message : '处理失败，请重试';
    }
  }
}
