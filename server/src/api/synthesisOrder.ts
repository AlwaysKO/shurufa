import type pg from 'pg';

export async function loadSynthesisOrder(pool: pg.Pool, userId: string): Promise<string[]> {
  const result = await pool.query('SELECT asset_order FROM synthesis_library_order WHERE user_id=$1', [userId]);
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
