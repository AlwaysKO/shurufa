import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';

// 与现有词库构建共用 server/package-lock.json 锁定的 pinyin-pro。
const require = createRequire(new URL('../../../server/package.json', import.meta.url));
const { pinyin } = require('pinyin-pro');
const texts = JSON.parse(readFileSync(0, 'utf8'));
const rows = texts.map(text => [text, pinyin(text, { toneType: 'none', type: 'array', v: true }).join(' ')]);
process.stdout.write(JSON.stringify(rows.filter(([text, reading]) =>
  /^[a-z]+(?: [a-z]+)+$/.test(reading) && reading.split(' ').length === text.length)));
