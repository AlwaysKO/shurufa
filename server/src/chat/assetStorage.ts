import { createHash, randomUUID } from 'node:crypto';
import { mkdir, rename, unlink, writeFile } from 'node:fs/promises';
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
  const existing = await pool.query<AssetRow>(
    'SELECT id FROM media_asset WHERE user_id = $1 AND sha256 = $2',
    [userId, input.sha256],
  );
  if (existing.rows[0]) {
    return { id: Number(existing.rows[0].id), sha256: input.sha256, duplicated: true };
  }

  const relativePath = join('chat', input.sha256.slice(0, 2), `${input.sha256}.${extension}`);
  const destination = join(process.cwd(), 'uploads', relativePath);
  const directory = join(process.cwd(), 'uploads', 'chat', input.sha256.slice(0, 2));
  const temporary = `${destination}.${randomUUID()}.tmp`;
  await mkdir(directory, { recursive: true });
  try {
    await writeFile(temporary, bytes, { flag: 'wx' });
    await rename(temporary, destination);
  } finally {
    await unlink(temporary).catch(() => {});
  }

  const inserted = await pool.query<AssetRow>(
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

  const raced = await pool.query<AssetRow>(
    'SELECT id FROM media_asset WHERE user_id = $1 AND sha256 = $2',
    [userId, input.sha256],
  );
  if (!raced.rows[0]) throw new Error('asset insert did not return a row');
  return { id: Number(raced.rows[0].id), sha256: input.sha256, duplicated: true };
}
