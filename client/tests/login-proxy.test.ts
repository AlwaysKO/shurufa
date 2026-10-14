import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { createServer as createViteServer } from 'vite';
import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
import config from '../vite.config';

it('开发代理覆盖访客自带XFF，后台审计拿到实际连接来源', async () => {
  const backend = createServer((req, res) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ forwarded: req.headers['x-forwarded-for'] })); });
  await new Promise<void>(resolve => backend.listen(0, '127.0.0.1', resolve));
  const target = `http://127.0.0.1:${(backend.address() as AddressInfo).port}`;
  const proxy = (config.server!.proxy!['/api'] as Exclude<import('vite').ProxyOptions, string>);
  const server = await createViteServer({ configFile: false, server: { host: '127.0.0.1', port: 0, proxy: { '/api': { ...proxy, target } } } });
  try {
    await server.listen();
    const port = (server.httpServer!.address() as AddressInfo).port;
    const response = await fetch(`http://127.0.0.1:${port}/api/test`, { headers: { 'X-Forwarded-For': '8.8.8.8' } });
    expect(await response.json()).toEqual({ forwarded: '127.0.0.1' });
  } finally { await server.close(); await new Promise<void>((resolve, reject) => backend.close(e => e ? reject(e) : resolve())); }
});
