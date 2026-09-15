import { describe, expect, it, vi } from 'vitest';
import { DeepSeekProvider } from './deepSeekProvider.js';

describe('DeepSeekProvider', () => {
  it('uses JSON output without exposing the key in returned errors', async () => {
    const fetcher = vi.fn(async (_url: string, init: RequestInit) => {
      expect(init.headers).toMatchObject({ Authorization: 'Bearer secret-value' });
      return new Response(JSON.stringify({
        choices: [{ message: { content: '```json\n{"replies":["收到"]}\n```' } }],
        usage: { prompt_tokens: 10, completion_tokens: 3 },
      }), { status: 200, headers: { 'content-type': 'application/json' } });
    });
    const provider = new DeepSeekProvider({ apiKey: 'secret-value', fetcher });
    const result = await provider.completeJson({ system: 's', user: 'u' });
    expect(result.json).toEqual({ replies: ['收到'] });
    expect(result.usage).toEqual({ prompt_tokens: 10, completion_tokens: 3 });
  });

  it('reports unavailable when no key is configured', async () => {
    const provider = new DeepSeekProvider({ apiKey: '' });
    expect(provider.available).toBe(false);
    await expect(provider.completeJson({ system: 's', user: 'u' })).rejects.toThrow('unavailable');
  });
});
