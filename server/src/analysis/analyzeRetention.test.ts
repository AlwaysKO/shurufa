import type pg from 'pg';
import { describe, expect, it, vi } from 'vitest';
import { analyzePhrases, generateCompletions } from './analyze.js';

const user = 'abcdef12-1234-4123-8123-abcdef123456';
function setup(fail = false) {
  const query = vi.fn(async (sql: string, _params?: unknown[]) => {
    if (sql.includes('FROM phrase_stat') && fail) throw new Error('analysis failed');
    if (sql.includes('RETURNING value')) return { rows: [{ value: '0' }], rowCount: 1 };
    if (sql.includes('MAX(version)')) return { rows: [{ v: 1 }], rowCount: 1 };
    return { rows: [], rowCount: 0 };
  });
  const db = { query, release: vi.fn() };
  const pool = { query, connect: vi.fn(async () => db) };
  return { pool, db };
}
describe('学习与补全清理的并发协调', () => {
  it.each(['phrases', 'completions'])('%s 在所有读取之前获取同一用户事务锁，并用同一连接提交', async phase => {
    const { pool, db } = setup();
    if (phase === 'phrases') await analyzePhrases(pool as unknown as pg.Pool, new Date(), user.toUpperCase());
    else await generateCompletions(pool as unknown as pg.Pool, user.toUpperCase());
    expect(pool.connect).toHaveBeenCalledTimes(1);
    expect(db.query.mock.calls[0]).toEqual(['BEGIN']);
    expect(db.query.mock.calls[1]).toEqual(["SELECT pg_advisory_xact_lock(hashtextextended('completion-retention:' || $1,0))", [user]]);
    expect(db.query.mock.calls.at(-1)).toEqual(['COMMIT']);
    expect(db.release).toHaveBeenCalledTimes(1);
  });
  it('分析失败回滚并释放连接，不留下半套候选', async () => {
    const { pool, db } = setup(true);
    await expect(generateCompletions(pool as unknown as pg.Pool, user)).rejects.toThrow('analysis failed');
    expect(db.query.mock.calls.at(-1)).toEqual(['ROLLBACK']);
    expect(db.release).toHaveBeenCalledTimes(1);
  });
});
