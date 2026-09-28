import type pg from 'pg';

export const SHARED_SYNTHESIS_OWNER = '00000000-0000-4000-8000-000000000000';

export async function loadSynthesisOrder(pool: pg.Pool): Promise<string[]> {
  const result = await pool.query('SELECT asset_order FROM synthesis_library_order WHERE user_id=$1', [SHARED_SYNTHESIS_OWNER]);
  return result.rows[0]?.asset_order ?? [];
}

// 未加入保存顺序的新上传置顶；删除项忽略，已有项相对顺序保留。
export function orderSynthesisAssets<T extends { id: string }>(uploaded: T[], system: T[], order: string[]): T[] {
  const byId = new Map([...uploaded, ...system].map(asset => [asset.id, asset]));
  const known = new Set(order);
  return [...uploaded.filter(asset => !known.has(asset.id)),
    ...order.flatMap(id => byId.has(id) ? [byId.get(id)!] : []),
    ...system.filter(asset => !known.has(asset.id))];
}
