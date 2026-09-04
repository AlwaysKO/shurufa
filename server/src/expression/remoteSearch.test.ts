import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import sharp from 'sharp';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CachedRemoteExpressionSearch } from './remoteSearch.js';

const roots: string[] = [];

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe('CachedRemoteExpressionSearch', () => {
  it('按关键词下载有效图片并在重复查询时完全命中磁盘缓存', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-search-'));
    roots.push(root);
    const red = await sharp({
      create: { width: 320, height: 180, channels: 4, background: '#ef4444' },
    }).png().toBuffer();
    const blue = await sharp({
      create: { width: 180, height: 320, channels: 4, background: '#3b82f6' },
    }).jpeg().toBuffer();
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url.startsWith('https://search.example.test')) {
        expect(new URL(url).searchParams.get('msg')).toBe('玻璃心');
        return new Response(JSON.stringify({
          code: 0,
          data: [
            { sticker_url: 'https://image.example.test/red.png', sticker_format: 'png' },
            { sticker_url: 'https://image.example.test/blue.jpg', sticker_format: 'jpg' },
          ],
        }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      if (url.endsWith('/red.png')) {
        return new Response(red, { status: 200, headers: { 'content-type': 'image/png' } });
      }
      if (url.endsWith('/blue.jpg')) {
        return new Response(blue, { status: 200, headers: { 'content-type': 'image/jpeg' } });
      }
      throw new Error(`unexpected URL: ${url}`);
    });
    const search = new CachedRemoteExpressionSearch({
      assetRoot: root,
      providerUrl: 'https://search.example.test/search',
      fetcher,
    });

    const first = await search.search(' 玻璃心 ', 8);

    expect(first).toHaveLength(2);
    expect(first.every((asset) => (
      asset.type === 'prebuilt'
      && asset.embeddedText === '玻璃心'
      && asset.keywords.includes('玻璃心')
      && asset.fileName.startsWith('search-cache/')
      && asset.thumbnailFileName?.startsWith('search-cache/') === true
    ))).toBe(true);
    for (const asset of first) {
      expect((await readFile(join(root, asset.fileName))).byteLength).toBeGreaterThan(0);
      expect((await readFile(join(root, asset.thumbnailFileName!))).byteLength).toBeGreaterThan(0);
    }
    expect(fetcher).toHaveBeenCalledTimes(3);

    fetcher.mockImplementation(async () => { throw new Error('缓存命中时不应联网'); });
    const second = await search.search('玻璃心', 8);

    expect(second).toEqual(first);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('丢弃非 HTTPS 地址、非图片响应和超出数量的结果', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-search-invalid-'));
    roots.push(root);
    const valid = await sharp({
      create: { width: 64, height: 64, channels: 4, background: '#22c55e' },
    }).webp().toBuffer();
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url.startsWith('https://search.example.test')) {
        return new Response(JSON.stringify({
          code: 0,
          data: [
            { sticker_url: 'http://image.example.test/insecure.png' },
            { sticker_url: 'https://image.example.test/not-image' },
            { sticker_url: 'https://image.example.test/valid.webp' },
            { sticker_url: 'https://image.example.test/ignored.webp' },
          ],
        }), { status: 200 });
      }
      if (url.endsWith('/not-image')) return new Response('html', { status: 200 });
      return new Response(valid, { status: 200, headers: { 'content-type': 'image/webp' } });
    });
    const search = new CachedRemoteExpressionSearch({
      assetRoot: root,
      providerUrl: 'https://search.example.test/search',
      fetcher,
    });

    const results = await search.search('测试', 1);

    expect(results).toHaveLength(1);
    expect(results[0].format).toBe('webp');
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('大量失效链接时限制下载尝试次数', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-search-attempts-'));
    roots.push(root);
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url.startsWith('https://search.example.test')) {
        return new Response(JSON.stringify({
          data: Array.from({ length: 100 }, (_, index) => ({
            sticker_url: `https://image.example.test/missing-${index}.png`,
          })),
        }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      return new Response('not found', { status: 404 });
    });
    const search = new CachedRemoteExpressionSearch({
      assetRoot: root,
      providerUrl: 'https://search.example.test/search',
      fetcher,
    });

    expect(await search.search('全部失效', 2)).toEqual([]);
    expect(fetcher.mock.calls.length).toBeLessThanOrEqual(5);
  });

  it('无 Content-Length 的超大图片会提前停止流式读取', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-search-stream-limit-'));
    roots.push(root);
    let pulls = 0;
    let cancelled = false;
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url.startsWith('https://search.example.test')) {
        return new Response(JSON.stringify({
          data: [{ sticker_url: 'https://image.example.test/huge.png' }],
        }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      const body = new ReadableStream<Uint8Array>({
        pull(controller) {
          pulls += 1;
          if (pulls > 20) {
            controller.close();
          } else {
            controller.enqueue(new Uint8Array(1024 * 1024));
          }
        },
        cancel() {
          cancelled = true;
        },
      });
      return new Response(body, { status: 200, headers: { 'content-type': 'image/png' } });
    });
    const search = new CachedRemoteExpressionSearch({
      assetRoot: root,
      providerUrl: 'https://search.example.test/search',
      fetcher,
    });

    expect(await search.search('超大图片', 1)).toEqual([]);
    expect(cancelled).toBe(true);
    expect(pulls).toBeLessThanOrEqual(7);
  });

  it('拒绝被 HTTPS 地址重定向到明文或私网的图片', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-search-redirect-'));
    roots.push(root);
    const valid = await sharp({
      create: { width: 64, height: 64, channels: 4, background: '#22c55e' },
    }).png().toBuffer();
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      if (String(input).startsWith('https://search.example.test')) {
        return new Response(JSON.stringify({
          data: [{ sticker_url: 'https://image.example.test/redirect.png' }],
        }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      const response = new Response(valid, {
        status: 200,
        headers: { 'content-type': 'image/png' },
      });
      Object.defineProperty(response, 'url', { value: 'http://127.0.0.1/private.png' });
      return response;
    });
    const search = new CachedRemoteExpressionSearch({
      assetRoot: root,
      providerUrl: 'https://search.example.test/search',
      fetcher,
    });

    expect(await search.search('重定向', 1)).toEqual([]);
  });

  it('整次搜索超时后中止上游请求', async () => {
    const root = await mkdtemp(join(tmpdir(), 'expression-search-timeout-'));
    roots.push(root);
    let aborted = false;
    const fetcher = vi.fn((_input: string | URL | Request, init?: RequestInit) => (
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          aborted = true;
          reject(new DOMException('aborted', 'AbortError'));
        }, { once: true });
      })
    ));
    const search = new CachedRemoteExpressionSearch({
      assetRoot: root,
      providerUrl: 'https://search.example.test/search',
      fetcher,
      requestTimeoutMs: 1_000,
      searchTimeoutMs: 20,
    });

    expect(await search.search('超时', 1)).toEqual([]);
    expect(aborted).toBe(true);
  });
});
