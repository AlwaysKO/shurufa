import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join, posix } from 'node:path';
import sharp from 'sharp';
import type { ExpressionAsset, ExpressionAssetFormat } from '../types/expression.js';

interface ProviderItem {
  sticker_url?: unknown;
  url?: unknown;
  image_url?: unknown;
}

interface CacheIndex {
  query: string;
  createdAt: number;
  assets: ExpressionAsset[];
}

export interface RemoteExpressionSearch {
  search(query: string, limit?: number): Promise<ExpressionAsset[]>;
}

export interface CachedRemoteExpressionSearchOptions {
  assetRoot: string;
  providerUrl: string;
  fetcher?: typeof fetch;
  now?: () => number;
  cacheTtlMs?: number;
  requestTimeoutMs?: number;
  searchTimeoutMs?: number;
}

const DEFAULT_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1_000;
const MAX_RESULTS = 12;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_PROVIDER_BYTES = 512 * 1024;
const MAX_IMAGE_PIXELS = 40_000_000;
const REQUEST_TIMEOUT_MS = 8_000;
const SEARCH_TIMEOUT_MS = 12_000;

function sha256(value: string | Uint8Array): string {
  return createHash('sha256').update(value).digest('hex');
}

function normalize(value: string): string {
  return value.trim().replace(/\s+/g, ' ');
}

function normalizedFormat(value: string | undefined): ExpressionAssetFormat | null {
  switch (value?.toLowerCase()) {
    case 'gif': return 'gif';
    case 'png': return 'png';
    case 'jpg':
    case 'jpeg': return 'jpg';
    case 'webp': return 'webp';
    default: return null;
  }
}

function providerUrls(body: unknown): string[] {
  if (!body || typeof body !== 'object') return [];
  const data = (body as { data?: unknown }).data;
  if (!Array.isArray(data)) return [];
  return data.map((item) => {
    if (!item || typeof item !== 'object') return '';
    const row = item as ProviderItem;
    const value = row.sticker_url ?? row.image_url ?? row.url;
    return typeof value === 'string' ? value : '';
  }).filter(Boolean);
}

function isSafeHttpsUrl(value: string): boolean {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:') return false;
    const hostname = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
    if (hostname === 'localhost' || hostname.endsWith('.local') || hostname === '::1') return false;
    const octets = hostname.split('.').map(Number);
    if (octets.length === 4 && octets.every((part) => Number.isInteger(part) && part >= 0 && part <= 255)) {
      if (octets[0] === 10 || octets[0] === 127 || octets[0] === 0) return false;
      if (octets[0] === 169 && octets[1] === 254) return false;
      if (octets[0] === 192 && octets[1] === 168) return false;
      if (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) return false;
    }
    return true;
  } catch {
    return false;
  }
}

async function readLimitedBody(response: Response, maxBytes: number): Promise<Uint8Array> {
  const declaredSize = Number(response.headers.get('content-length') ?? 0);
  if (Number.isFinite(declaredSize) && declaredSize > maxBytes) {
    await response.body?.cancel().catch(() => undefined);
    throw new Error('response is too large');
  }
  const reader = response.body?.getReader();
  if (!reader) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel('response is too large').catch(() => undefined);
        throw new Error('response is too large');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

async function fetchBytesWithTimeout(
  fetcher: typeof fetch,
  url: string,
  maxBytes: number,
  timeoutMs: number,
  parentSignal: AbortSignal,
  headers: Record<string, string> = {},
): Promise<{ response: Response; bytes: Uint8Array }> {
  const controller = new AbortController();
  const abort = () => controller.abort(parentSignal.reason);
  if (parentSignal.aborted) abort();
  else parentSignal.addEventListener('abort', abort, { once: true });
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetcher(url, {
      headers,
      signal: controller.signal,
      // 不让已校验的 HTTPS URL 在请求阶段跳转到明文或私网。
      redirect: 'error',
    });
    const bytes = await readLimitedBody(response, maxBytes);
    return { response, bytes };
  } finally {
    clearTimeout(timeout);
    parentSignal.removeEventListener('abort', abort);
  }
}

export class CachedRemoteExpressionSearch implements RemoteExpressionSearch {
  private readonly fetcher: typeof fetch;
  private readonly now: () => number;
  private readonly cacheTtlMs: number;
  private readonly requestTimeoutMs: number;
  private readonly searchTimeoutMs: number;

  constructor(private readonly options: CachedRemoteExpressionSearchOptions) {
    this.fetcher = options.fetcher ?? fetch;
    this.now = options.now ?? Date.now;
    this.cacheTtlMs = options.cacheTtlMs ?? DEFAULT_CACHE_TTL_MS;
    this.requestTimeoutMs = options.requestTimeoutMs ?? REQUEST_TIMEOUT_MS;
    this.searchTimeoutMs = options.searchTimeoutMs ?? SEARCH_TIMEOUT_MS;
    const provider = new URL(options.providerUrl);
    if (provider.protocol !== 'https:') throw new Error('expression search provider must use HTTPS');
  }

  async search(queryValue: string, requestedLimit = MAX_RESULTS): Promise<ExpressionAsset[]> {
    const query = normalize(queryValue);
    if (!query) return [];
    const limit = Math.min(Math.max(requestedLimit, 0), MAX_RESULTS);
    if (limit === 0) return [];
    const queryHash = sha256(query).slice(0, 24);
    const cached = await this.readCache(queryHash, query);
    if (cached) return cached.slice(0, limit);

    const searchController = new AbortController();
    const searchTimeout = setTimeout(() => searchController.abort(), this.searchTimeoutMs);
    try {
      const providerUrl = new URL(this.options.providerUrl);
      providerUrl.searchParams.set('msg', query);
      providerUrl.searchParams.set('page', '1');
      const { response: providerResponse, bytes: providerBytes } = await fetchBytesWithTimeout(
        this.fetcher,
        providerUrl.toString(),
        MAX_PROVIDER_BYTES,
        this.requestTimeoutMs,
        searchController.signal,
        {
          accept: 'application/json',
          'user-agent': 'ShurufaExpressionSearch/1.0',
        },
      );
      if (!providerResponse.ok || (providerResponse.url && !isSafeHttpsUrl(providerResponse.url))) return [];
      const urls = providerUrls(JSON.parse(new TextDecoder().decode(providerBytes)))
        .filter(isSafeHttpsUrl)
        .slice(0, limit * 2);
      const assets: ExpressionAsset[] = [];
      for (const imageUrl of urls) {
        if (assets.length >= limit || searchController.signal.aborted) break;
        const asset = await this.download(imageUrl, query, queryHash, searchController.signal)
          .catch(() => null);
        if (asset) assets.push(asset);
      }
      if (assets.length > 0) await this.writeCache(queryHash, { query, createdAt: this.now(), assets });
      return assets;
    } catch {
      return [];
    } finally {
      clearTimeout(searchTimeout);
    }
  }

  private async download(
    imageUrl: string,
    query: string,
    queryHash: string,
    searchSignal: AbortSignal,
  ): Promise<ExpressionAsset | null> {
    if (!isSafeHttpsUrl(imageUrl)) return null;
    const { response, bytes } = await fetchBytesWithTimeout(
      this.fetcher,
      imageUrl,
      MAX_IMAGE_BYTES,
      this.requestTimeoutMs,
      searchSignal,
      {
        accept: 'image/avif,image/webp,image/png,image/jpeg,image/gif',
        'user-agent': 'ShurufaExpressionSearch/1.0',
      },
    );
    if (response.url && !isSafeHttpsUrl(response.url)) return null;
    if (!response.ok || !response.headers.get('content-type')?.toLowerCase().startsWith('image/')) {
      return null;
    }
    if (bytes.byteLength === 0) return null;
    const metadata = await sharp(bytes, { animated: true, limitInputPixels: MAX_IMAGE_PIXELS }).metadata();
    const format = normalizedFormat(metadata.format);
    if (!format || !metadata.width || !metadata.height) return null;

    const imageHash = sha256(imageUrl).slice(0, 20);
    const baseName = `${queryHash}-${imageHash}`;
    const fileName = posix.join('search-cache', `${baseName}.${format}`);
    const thumbnailFileName = posix.join('search-cache', `${baseName}-thumb.webp`);
    const target = join(this.options.assetRoot, fileName);
    const thumbnail = join(this.options.assetRoot, thumbnailFileName);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, bytes);
    await sharp(bytes, { pages: 1, limitInputPixels: MAX_IMAGE_PIXELS })
      .rotate()
      .resize(320, 320, { fit: 'cover', position: 'attention' })
      .webp({ quality: 84 })
      .toFile(thumbnail);

    return {
      id: `search-${baseName}`,
      type: 'prebuilt',
      format,
      version: 'search-v1',
      fileName,
      thumbnailFileName,
      sha256: sha256(bytes),
      width: metadata.width,
      height: metadata.pageHeight ?? metadata.height,
      keywords: [query],
      emotions: [],
      // 搜索结果与查询一一关联；复用精确关联字段，避免手机端再次叠字。
      embeddedText: query,
      textSafeArea: null,
      layout: null,
      heat: 0,
    };
  }

  private indexPath(queryHash: string): string {
    return join(this.options.assetRoot, '.search-index', `${queryHash}.json`);
  }

  private async readCache(queryHash: string, query: string): Promise<ExpressionAsset[] | null> {
    try {
      const index = JSON.parse(await readFile(this.indexPath(queryHash), 'utf8')) as CacheIndex;
      if (index.query !== query || this.now() - index.createdAt > this.cacheTtlMs) return null;
      if (!index.assets.every((asset) => (
        existsSync(join(this.options.assetRoot, asset.fileName))
        && asset.thumbnailFileName !== null
        && existsSync(join(this.options.assetRoot, asset.thumbnailFileName))
      ))) return null;
      return index.assets;
    } catch {
      return null;
    }
  }

  private async writeCache(queryHash: string, index: CacheIndex): Promise<void> {
    const target = this.indexPath(queryHash);
    await mkdir(dirname(target), { recursive: true });
    const part = `${target}.${process.pid}.${Date.now()}.part`;
    await writeFile(part, JSON.stringify(index));
    await rename(part, target);
  }
}
