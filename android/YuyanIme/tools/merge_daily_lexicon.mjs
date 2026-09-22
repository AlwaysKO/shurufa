/** 构建期 AOSP 公共词典合并：不生成任何个人点击或最近使用记录。 */
import {readFileSync,writeFileSync} from 'node:fs';
import {gzipSync,gunzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const SOURCE_SHA256='408700f28a56091fa07f3b849a0f134fbfc71e6b2ae9b3f52973a5b076f599ff';
const MIN_DAILY_FREQUENCY=200; // 控制低频补充范围，不等同日常词分类，不按示例词开白名单。
// 源中的历史读音校订；不按单字穷举扩写，也不影响候选拼写规则。
const corrections=new Map([['曝光',new Set(['bao guang'])],['补给',new Set(['bu ji'])]]);
const key=(text,pinyin)=>`${text}\t${pinyin}`;
const validReading=(text,reading)=>/^[\u4e00-\u9fff]{2,6}$/.test(text) &&
 reading.split(' ').length===text.length && /^[a-z]+(?: [a-z]+)*$/.test(reading);
export function mergeDailyLexicon(base,source) {
 const entries=new Map(),baseFrequency=new Map();
 for(const line of base.split(/\r?\n/)) {
  const [text,pinyin,freq]=line.split('\t'),frequency=Number(freq);
  if(!text || !pinyin || !validReading(text,pinyin) || !Number.isSafeInteger(frequency) || frequency<0) continue;
  entries.set(key(text,pinyin),frequency);
  baseFrequency.set(text,Math.max(baseFrequency.get(text)??0,frequency));
 }
 const daily=new Map();
 for(const line of source.replace(/^\uFEFF/,'').split(/\r?\n/)) {
  const [text,rawFrequency,flag,...syllables]=line.trim().split(/\s+/);
  const frequency=Number(rawFrequency),pinyin=syllables.join(' ').toLowerCase().replaceAll('ü','v');
  if(flag!=='0' || !Number.isFinite(frequency) || frequency<MIN_DAILY_FREQUENCY ||
      !validReading(text??'',pinyin) || (corrections.has(text)&&!corrections.get(text).has(pinyin))) continue;
  const id=key(text,pinyin);daily.set(id,Math.max(daily.get(id)??0,frequency));
 }
 const dailyFrequency=new Map();
 for(const [id,freq] of daily) {
  const text=id.split('\t')[0];dailyFrequency.set(text,Math.max(dailyFrequency.get(text)??0,freq));
 }
 // 两套语料词频尺度不同：按重叠词的频率比例中位数校准，不直接相加。
 const ratios=[...dailyFrequency].filter(([text])=>(baseFrequency.get(text)??0)>0)
  .map(([text,freq])=>baseFrequency.get(text)/freq).sort((a,b)=>a-b);
 const middle=Math.floor(ratios.length/2);
 const frequencyScale=ratios.length ? (ratios.length%2 ? ratios[middle] : (ratios[middle-1]+ratios[middle])/2) : 1;
 // 同词优先使用输入法源提供的整词读音；没有新来源的词仍完整保留。
 for(const id of entries.keys()) if(dailyFrequency.has(id.split('\t')[0])) entries.delete(id);
 for(const [id,freq] of daily) entries.set(id,Math.max(1,Math.min(Number.MAX_SAFE_INTEGER,Math.round(freq*frequencyScale))));
 const lexicon=[...entries].sort(([a],[b])=>a<b?-1:a>b?1:0).map(([id,freq])=>`${id}\t${freq}`).join('\n')+'\n';
 return {lexicon,stats:{sourceReadings:daily.size,sourceWords:dailyFrequency.size,
  addedWords:[...dailyFrequency.keys()].filter(text=>!baseFrequency.has(text)).length,
  overlapWords:ratios.length,frequencyScale,outputReadings:entries.size}};
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
 const [baseline,aosp,output]=process.argv.slice(2);
 if(!baseline||!aosp||!output||baseline===output) throw new Error('用法：node merge_daily_lexicon.mjs baseline.tsv.gzip aosp-source.txt.gz output.tsv.gzip（输入输出不能相同）');
 const raw=gunzipSync(readFileSync(aosp));
 if(createHash('sha256').update(raw).digest('hex')!==SOURCE_SHA256) throw new Error('AOSP源哈希不符，禁止静默更换词源');
 const {lexicon,stats}=mergeDailyLexicon(gunzipSync(readFileSync(baseline)).toString('utf8'),raw.toString('utf16le'));
 writeFileSync(output,gzipSync(lexicon,{level:9}));console.log(JSON.stringify(stats,null,2));
}
