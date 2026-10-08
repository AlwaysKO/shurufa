import { createHash } from 'node:crypto';
import { mkdtemp, readdir, rm, unlink, writeFile, readFile, stat, symlink, mkdir, utimes } from 'node:fs/promises';
import * as fsPromises from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { storeAsset } from './assetStorage.js';

vi.mock('node:fs/promises', async importOriginal => {
  const actual = await importOriginal<typeof import('node:fs/promises')>();
  return { ...actual, rename: vi.fn(actual.rename) };
});

let pool: pg.Pool;
let root: string;
let userId: string;

const pngBytes = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

beforeEach(async () => {
  vi.mocked(fsPromises.rename).mockReset();
  const database = newDb();
  // pg-mem不实现LOCK TABLE；真实并发锁行为另由deviceControls.postgres.test覆盖。
  database.public.interceptQueries(sql => sql === 'LOCK TABLE media_asset IN ROW EXCLUSIVE MODE' ? [] : null);
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(
    new URL('../../migrations/007_chat_capture.sql', import.meta.url),
    'utf8',
  ));
  root = await mkdtemp(join(tmpdir(), 'chat-asset-'));
  vi.spyOn(process, 'cwd').mockReturnValue(root);
  userId = crypto.randomUUID();
});

afterEach(async () => {
  vi.restoreAllMocks();
  await pool.end();
  await rm(root, { recursive: true, force: true });
});

describe('storeAsset', () => {
  const input = () => ({ sha256: sha256(pngBytes), mime_type: 'image/png', file_base64: pngBytes.toString('base64') });
  const destination = () => join(root, 'uploads', 'chat', sha256(pngBytes).slice(0, 2), `${sha256(pngBytes)}.png`);

  it.each(['missing', 'corrupt'])('重复上传修复%s文件，保留资源ID、创建时间和既有MIME路径', async state => {
    const first = await storeAsset(pool, userId, input());
    const before = (await pool.query('SELECT * FROM media_asset WHERE id=$1', [first.id])).rows[0];
    if (state === 'missing') await unlink(destination()); else await writeFile(destination(), Buffer.alloc(pngBytes.length, 1));
    // 同样真实字节，历史客户端声明变化不能把既有PNG路径换成WEBP。
    const repaired = await storeAsset(pool, userId, { ...input(), mime_type: 'image/webp' });
    expect(repaired).toEqual({ ...first, duplicated: true });
    expect(await readFile(destination())).toEqual(pngBytes);
    expect((await pool.query('SELECT * FROM media_asset WHERE id=$1', [first.id])).rows[0]).toEqual(before);
    expect(await readdir(join(destination(), '..'))).toEqual([`${input().sha256}.png`]);
  });

  it('完整文件不重写，保留文件修改时间和inode', async () => {
    await storeAsset(pool, userId, input());
    await utimes(destination(), new Date('2020-01-01'), new Date('2020-01-01'));
    const before = await stat(destination());
    await storeAsset(pool, userId, input());
    const after = await stat(destination());
    expect([after.ino, after.mtimeMs]).toEqual([before.ino, before.mtimeMs]);
  });

  it.each(['../outside.png', 'chat/../outside.png', '/tmp/outside.png', `chat/aa/${'a'.repeat(64)}.png`])('已有路径异常拒绝确认：%s', async path => {
    const first = await storeAsset(pool, userId, input());
    await pool.query('UPDATE media_asset SET storage_path=$1 WHERE id=$2', [path, first.id]);
    await expect(storeAsset(pool, userId, input())).rejects.toThrow();
    expect(await readFile(destination())).toEqual(pngBytes);
  });

  it.each(['file', 'shard', 'chat'])('拒绝跟随%s符号链接，即便链接目标字节正确', async level => {
    await storeAsset(pool, userId, input());
    const outside = join(root, 'outside');
    await mkdir(outside);
    const target = level === 'file' ? join(outside, 'source.png') : outside;
    if (level === 'file') await writeFile(target, pngBytes);
    const link = level === 'file' ? destination() : level === 'shard' ? join(destination(), '..') : join(root, 'uploads', 'chat');
    await rm(link, { recursive: true, force: true });
    await symlink(target, link);
    await expect(storeAsset(pool, userId, input())).rejects.toThrow();
    if (level === 'file') expect(await readFile(target)).toEqual(pngBytes);
    else expect(await readdir(outside)).toEqual([]);
  });

  it('原子替换失败不确认，保留损坏文件并清理临时文件，可再次重试', async () => {
    const first = await storeAsset(pool, userId, input());
    await writeFile(destination(), 'corrupt');
    const replace = vi.spyOn(fsPromises, 'rename').mockRejectedValueOnce(Object.assign(Error('cannot replace'), { code: 'EACCES' }));
    await expect(storeAsset(pool, userId, input())).rejects.toThrow('cannot replace');
    expect(await readFile(destination(), 'utf8')).toBe('corrupt');
    expect(await readdir(join(destination(), '..'))).toEqual([`${input().sha256}.png`]);
    replace.mockRestore();
    expect(await storeAsset(pool, userId, input())).toEqual({ ...first, duplicated: true });
    expect(await readFile(destination())).toEqual(pngBytes);
  });

  it('其他设备同哈希数据库行不能替代当前设备资源登记', async () => {
    const first = await storeAsset(pool, userId, input());
    const second = await storeAsset(pool, crypto.randomUUID(), input());
    expect(second.id).not.toBe(first.id);
    expect(second.duplicated).toBe(false);
    expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(2);
  });

  it('部署uploads根链接可用，其下资源仍校验修复', async () => {
    const shared = join(root, 'shared-uploads');
    await mkdir(shared);
    await symlink(shared, join(root, 'uploads'));
    const first = await storeAsset(pool, userId, input());
    await unlink(destination());
    expect(await storeAsset(pool, userId, input())).toEqual({ ...first, duplicated: true });
    expect(await readFile(destination())).toEqual(pngBytes);
  });

  it('按服务端收到的实际字节校验 SHA-256', async () => {
    await expect(storeAsset(pool, userId, {
      sha256: '0'.repeat(64),
      mime_type: 'image/png',
      file_base64: pngBytes.toString('base64'),
    })).rejects.toThrow('sha256');

    const result = await storeAsset(pool, userId, {
      sha256: sha256(pngBytes),
      mime_type: 'image/png',
      file_base64: pngBytes.toString('base64'),
    });
    expect(result.sha256).toBe(sha256(pngBytes));
  });

  it('同一文件上传两次只产生一个资源和一个磁盘文件', async () => {
    const input = {
      sha256: sha256(pngBytes),
      mime_type: 'image/png',
      file_base64: pngBytes.toString('base64'),
    };

    expect(await storeAsset(pool, userId, input)).toMatchObject({ duplicated: false });
    expect(await storeAsset(pool, userId, input)).toMatchObject({ duplicated: true });
    expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(1);
    const files = await readdir(join(root, 'uploads', 'chat', input.sha256.slice(0, 2)));
    expect(files).toEqual([`${input.sha256}.png`]);
  });

  it.each([
    ['非允许 MIME', { mime_type: 'image/jpeg', file_base64: pngBytes.toString('base64') }],
    ['空文件', { mime_type: 'image/png', file_base64: '' }],
    ['超过 5MB', { mime_type: 'image/webp', file_base64: Buffer.alloc(5 * 1024 * 1024 + 1).toString('base64') }],
  ])('拒绝%s', async (_name, invalid) => {
    await expect(storeAsset(pool, userId, {
      sha256: sha256(Buffer.from(invalid.file_base64, 'base64')),
      ...invalid,
    })).rejects.toThrow();
  });
});
