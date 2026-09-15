/** 构建期工具：运行时仅读取生成资产，不访问网络。 */
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
const require = createRequire(new URL('../../../server/package.json', import.meta.url));
const { pinyin } = require('pinyin-pro');

export function buildLexicon(source) {
  const words = new Map();
  for (const line of source.split(/\r?\n/)) {
    const [text, frequency, tag] = line.split(/\s+/);
    const freq = Number(frequency);
    if (!/^[\u4e00-\u9fff]{2,6}$/.test(text) || !Number.isFinite(freq)) continue;
    // 高频普通词 + 固定表达/成语，避免仅按总频率过滤掉低频多字词。
    if (freq < 10 && !(freq >= 3 && ['l', 'i', 'j'].includes(tag))) continue;
    const syllables = pinyin(text, { toneType: 'none', type: 'array', v: true });
    if (syllables.length !== text.length || !syllables.every(s => /^[a-z]+$/.test(s))) continue;
    words.set(text, `${text}\t${syllables.join(' ')}\t${freq}`);
  }
  return [...words.values()].sort().join('\n') + '\n';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [source, output] = process.argv.slice(2);
  if (!source || !output) throw new Error('用法: node generate_t9_lexicon.mjs jieba-dict.txt output.tsv.gzip');
  const data = buildLexicon(readFileSync(source, 'utf8'));
  writeFileSync(output, gzipSync(data, { level: 9 }));
  console.log(`离线词条 ${data.trim().split('\n').length}，原始字节 ${Buffer.byteLength(data)}`);
}
