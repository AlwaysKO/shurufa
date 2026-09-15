import { createHash } from 'node:crypto';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { storeAsset } from './assetStorage.js';

let pool: pg.Pool;
let root: string;
let userId: string;

const pngBytes = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

beforeEach(async () => {
  const database = newDb();
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
