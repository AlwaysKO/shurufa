import { createHash, randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, mkdir, open, realpath, rename, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import type pg from 'pg';

const MAX_ASSET_BYTES = 5 * 1024 * 1024;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const MIME_EXTENSIONS = new Map([
  ['image/png', 'png'],
  ['image/webp', 'webp'],
]);

export class AssetValidationError extends Error {}

export interface StoreAssetInput {
  sha256: string;
  mime_type: string;
  file_base64: string;
  perceptual_hash?: string;
  width?: number;
  height?: number;
}

export interface StoredAssetResult {
  id: number;
  sha256: string;
  duplicated: boolean;
}

interface AssetRow {
  id: string | number;
  mime_type: string;
  storage_path: string;
}

/** uploads may be the deployment's shared-directory link; descendants must not be links. */
async function ensureAssetFile(relativePath: string, sha256: string, bytes: Buffer): Promise<void> {
  const uploads = join(process.cwd(), 'uploads');
  await mkdir(uploads, { recursive: true });
  let directory = await realpath(uploads);
  for (const part of ['chat', sha256.slice(0, 2)]) {
    directory = join(directory, part);
    await mkdir(directory).catch(error => { if (error.code !== 'EEXIST') throw error; });
    if (!(await lstat(directory)).isDirectory()) throw new Error('Asset directory must not be a symbolic link');
  }
  const destination = join(directory, relativePath.split('/').at(-1)!);
  async function checkDestination(): Promise<boolean> {
    try {
      if (!(await lstat(destination)).isFile()) throw new Error('Asset path must be a regular file');
      return true;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false;
      throw error;
    }
  }
  if (await checkDestination()) {
    const existing = await open(destination, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const info = await existing.stat();
      if (!info.isFile()) throw new Error('Asset path must be a regular file');
      if (info.size === bytes.length && createHash('sha256').update(await existing.readFile()).digest('hex') === sha256) return;
    } finally { await existing.close(); }
  }
  const temporary = `${destination}.${randomUUID()}.tmp`;
  try {
    const file = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o666);
    try { await file.writeFile(bytes); await file.sync(); } finally { await file.close(); }
    await checkDestination();
    await rename(temporary, destination);
    const parent = await open(directory, constants.O_RDONLY | constants.O_NOFOLLOW);
    try { await parent.sync(); } finally { await parent.close(); }
  } finally { await unlink(temporary).catch(() => {}); }
}

async function verifyExisting(row: AssetRow, sha256: string, bytes: Buffer): Promise<void> {
  const extension = MIME_EXTENSIONS.get(row.mime_type);
  const expected = `chat/${sha256.slice(0, 2)}/${sha256}.${extension}`;
  if (!extension || row.storage_path !== expected) throw new Error('Stored asset path or MIME is invalid');
  await ensureAssetFile(row.storage_path, sha256, bytes);
}

function decodeAndValidate(input: StoreAssetInput): { bytes: Buffer; extension: string } {
  const extension = MIME_EXTENSIONS.get(input?.mime_type);
  if (!extension) throw new AssetValidationError('mime_type is not allowed');
  if (typeof input.file_base64 !== 'string') {
    throw new AssetValidationError('file_base64 is required');
  }
  const bytes = Buffer.from(input.file_base64, 'base64');
  if (bytes.length === 0) throw new AssetValidationError('file must not be empty');
  if (bytes.length > MAX_ASSET_BYTES) {
    throw new AssetValidationError('file exceeds the 5MB limit');
  }
  if (typeof input.sha256 !== 'string' || !SHA256_PATTERN.test(input.sha256)) {
    throw new AssetValidationError('sha256 is invalid');
  }
  const actualSha256 = createHash('sha256').update(bytes).digest('hex');
  if (actualSha256 !== input.sha256) {
    throw new AssetValidationError('sha256 does not match uploaded bytes');
  }
  return { bytes, extension };
}

export async function storeAsset(
  pool: pg.Pool,
  userId: string,
  input: StoreAssetInput,
): Promise<StoredAssetResult> {
  const { bytes, extension } = decodeAndValidate(input);
  const db = await pool.connect();
  try {
    await db.query('BEGIN');
    // 先取得写锁再触碰内容寻址文件，与设备删除的附件清理互斥。
    await db.query('LOCK TABLE media_asset IN ROW EXCLUSIVE MODE');
    const result = await storeLocked();
    await db.query('COMMIT');
    return result;
  } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
  finally { db.release(); }

  async function storeLocked(): Promise<StoredAssetResult> {
    const existing = await db.query<AssetRow>(
      'SELECT id,mime_type,storage_path FROM media_asset WHERE user_id = $1 AND sha256 = $2',
      [userId, input.sha256],
    );
    if (existing.rows[0]) {
      await verifyExisting(existing.rows[0], input.sha256, bytes);
      return { id: Number(existing.rows[0].id), sha256: input.sha256, duplicated: true };
    }

    const relativePath = join('chat', input.sha256.slice(0, 2), `${input.sha256}.${extension}`);
    await ensureAssetFile(relativePath, input.sha256, bytes);

    const inserted = await db.query<AssetRow>(
      `INSERT INTO media_asset
         (user_id, sha256, perceptual_hash, mime_type, storage_path,
          byte_size, width, height)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
       ON CONFLICT (user_id, sha256) DO NOTHING
       RETURNING id`,
      [
        userId,
        input.sha256,
        input.perceptual_hash ?? null,
        input.mime_type,
        relativePath,
        bytes.length,
        input.width ?? null,
        input.height ?? null,
      ],
    );
    if (inserted.rows[0]) {
      return { id: Number(inserted.rows[0].id), sha256: input.sha256, duplicated: false };
    }

    const raced = await db.query<AssetRow>(
      'SELECT id,mime_type,storage_path FROM media_asset WHERE user_id = $1 AND sha256 = $2',
      [userId, input.sha256],
    );
    if (!raced.rows[0]) throw new Error('asset insert did not return a row');
    await verifyExisting(raced.rows[0], input.sha256, bytes);
    return { id: Number(raced.rows[0].id), sha256: input.sha256, duplicated: true };
  }
}
