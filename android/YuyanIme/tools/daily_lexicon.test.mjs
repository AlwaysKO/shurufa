import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {gunzipSync} from 'node:zlib';

test('内置词库能离线召回原来缺失的日常词及明确读音',()=>{
 const text=gunzipSync(readFileSync(new URL('../yuyansdk/src/main/assets/completion/t9_lexicon.tsv.gzip',import.meta.url))).toString('utf8');
 for(const [word,reading] of [['买菜','mai cai'],['洗碗','xi wan'],['多少钱','duo shao qian'],['收货','shou huo'],['到家','dao jia']]) {
  assert.ok(text.includes(`${word}\t${reading}\t`),`${word} 必须内置而非运行时联网`);
 }
});

test('生成器校准频率、保留旧词且仅接受有依据的音节',async()=>{
 const {mergeDailyLexicon}=await import('./merge_daily_lexicon.mjs');
 const base='我们\two men\t1000\n买菜\tmai cai\t100\n旧词\tjiu ci\t25\n';
 const source='我们 2000 0 wo men\n买菜 200 0 mai cai\n洗碗 400 0 xi wan\n生僻 199 0 sheng pi\n错音 300 0 cuo\n错误 NaN 0 cuo wu\n繁體 999 1 fan ti\n';
 const {lexicon,stats}=mergeDailyLexicon(base,source);
 assert.equal(stats.frequencyScale,0.5);
 assert.ok(lexicon.includes('洗碗\txi wan\t200\n'));
 assert.ok(lexicon.includes('旧词\tjiu ci\t25\n'));
 for(const word of ['生僻','错音','错误','繁體']) assert.ok(!lexicon.includes(word));
 assert.equal(mergeDailyLexicon(base,source+source).lexicon,lexicon);
 assert.equal(mergeDailyLexicon(base,source.split('\n').reverse().join('\n')).lexicon,lexicon);
});
test('同词保留源中多读音但不保留已知错误音，不叠加公共频率',async()=>{
 const {mergeDailyLexicon}=await import('./merge_daily_lexicon.mjs');
 const {lexicon}=mergeDailyLexicon('补给\tbu gei\t100\n银行\tyin xing\t100\n',
  '补给 500 0 bu gei\n补给 500 0 bu ji\n银行 500 0 yin hang\n长大 500 0 chang da\n长大 600 0 zhang da\n');
 assert.ok(lexicon.includes('补给\tbu ji\t'));
 assert.ok(!lexicon.includes('bu gei'));assert.ok(!lexicon.includes('yin xing'));
 assert.ok(lexicon.includes('长大\tchang da\t'));assert.ok(lexicon.includes('长大\tzhang da\t'));
});
