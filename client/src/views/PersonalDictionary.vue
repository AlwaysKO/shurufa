<script setup lang="ts">
import { useConfirmation } from '../confirmation';
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { dictionaryApi, type DictionaryDevice, type DictionaryEntry, type DictionaryFilter, type DictionaryStatus, type DictionarySyncRequest } from '../api/personalDictionary';
import './content-library.css';
const askConfirmation = useConfirmation();

const devices = ref<DictionaryDevice[]>([]), rows = ref<DictionaryEntry[]>([]);
const total = ref(0), totalWords = ref<number>(), page = ref(1), deviceId = ref(''), q = ref(''), status = ref('');
const view = ref('merged');
const selected = ref<string[]>([]), targets = ref<string[]>([]), allSelected = ref(false);
const busy = ref(false), loading = ref(false), loaded = ref(false), error = ref(''), notice = ref('');
const newWord = ref(''), newPinyin = ref('');
let generation = 0, alive = true;
const canManage = computed(() => devices.value.some(d => d.in_group) && devices.value.filter(d => d.in_group).every(d => d.restore_enabled !== false));
const pageCount = computed(() => Math.max(1,Math.ceil(total.value/50)));
const hasSelection = computed(() => allSelected.value || selected.value.length > 0);
const label = (d:DictionaryDevice) => d.dashboard_name || [d.brand,d.model].filter(Boolean).join(' ') || d.name || d.device_id;
const deviceName = (id:string) => id ? devices.value.find(d => d.device_id===id) ? label(devices.value.find(d => d.device_id===id)!) : id : '手机来源未记录';
const date = (s:string|null|undefined) => s ? new Date(s).toLocaleString('zh-CN') : '尚未确认';
const statuses = {enabled:'启用',disabled:'停用',deleted:'已删除'};
const migration: Record<string,string> = {complete:'读取完成',permission_denied:'没有读取权限',unavailable:'系统词典不可用',failed:'读取失败',not_attempted:'尚未尝试'};
const filter = (): DictionaryFilter => ({...(deviceId.value ? {device_id:deviceId.value} : {}),...(q.value.trim() ? {q:q.value.trim()} : {}),...(status.value ? {status:status.value} : {})});
const sourceLabel = (s:string) => ({dashboard:'后台手动添加',system_dictionary:'系统词典导入',selection:'实际选词学习',rime:'Rime 选词学习'}[s] || s);
const source = (r:DictionaryEntry) => r.sources?.length ? r.sources.map(sourceLabel).join('、') : r.kind==='merged' ? '合并来源（详见明细）' : r.source==='dashboard' || r.source==='system_dictionary' ? sourceLabel(r.source) : r.kind==='choice' ? '实际选词学习' : '选词读音';
const rowDevice = (r:DictionaryEntry) => r.device_ids?.length ? r.device_ids.map(deviceName).join('、') : r.device_id ? deviceName(r.device_id) : r.source==='dashboard' || r.sources?.every(s=>s==='dashboard') && r.sources.length ? '—（后台添加）' : '手机来源未记录';
const hasChoices = (r:DictionaryEntry) => r.kind==='choice' || r.kind==='merged' && (r.has_choices ?? r.count>0);
function clearSelection() {selected.value=[];allSelected.value=false;}
function selectPage() {
  if(busy.value || loading.value || !loaded.value) return;
  allSelected.value=false;selected.value=[...new Set(rows.value.map(r=>r.text))];
}
function selectAll() {
  if(busy.value || loading.value || !loaded.value || !total.value) return;
  selected.value=[];allSelected.value=true;
}
function toggleWord(text:string,checked:boolean) {
  if(busy.value || loading.value || !loaded.value || allSelected.value) return;
  selected.value=checked ? [...new Set([...selected.value,text])] : selected.value.filter(t=>t!==text);
}
function toggleTarget(id:string,checked:boolean) {
  if(busy.value || loading.value) return;
  notice.value='';targets.value=checked ? [...new Set([...targets.value,id])] : targets.value.filter(t=>t!==id);
}
async function load(targetPage=page.value) {
  if(!alive) return;
  const version=++generation; loading.value=true; loaded.value=false; error.value='';
  try {
    const [directory,result]=await Promise.all([dictionaryApi.devices(),dictionaryApi.entries({...filter(),page:targetPage,view:view.value})]);
    if(version!==generation || !alive) return false;
    devices.value=directory.devices; rows.value=result.entries; total.value=result.total; totalWords.value=result.total_words; page.value=targetPage;
    targets.value=targets.value.filter(id=>directory.devices.some(d=>d.device_id===id));loaded.value=true;return true;
  } catch(e) { if(version===generation && alive) error.value=(e as Error).message; return false; }
  finally {if(version===generation && alive) loading.value=false;}
}
watch([deviceId,q,status,view],()=>{clearSelection();notice.value='';void load(1);});
function filterDevice(id:string) {if(busy.value) return;deviceId.value=id;view.value=id ? 'raw' : 'merged';}
async function addWord() {
  if(busy.value || loading.value) return;
  const text=newWord.value.trim(), pinyin=newPinyin.value.trim();
  if(!text || !pinyin) {error.value='请输入词语及逐字拼音，例如：泰鲮 / tai ling。';return;}
  busy.value=true;error.value='';notice.value='';
  try {
    const result=await dictionaryApi.addWord({text,pinyin});if(!alive) return;
    deviceId.value='';q.value=text;status.value='';view.value='merged';
    await nextTick();
    const refreshed=await load(1);if(!alive) return;
    if(refreshed) selected.value=[text];
    newWord.value='';newPinyin.value='';
    notice.value=`${result.created ? '已添加' : '词语已存在'}于本站词库${refreshed ? '并选中' : '，列表刷新失败，请重试查询'}；请选择目标手机，再点击增量同步。尚未发送到手机。`;
  } catch(e) {if(alive) error.value=(e as Error).message;} finally {if(alive) busy.value=false;}
}
async function syncSelected() {
  if(busy.value || loading.value || !loaded.value || !hasSelection.value || !targets.value.length) return;
  const ids=[...targets.value];
  const request:DictionarySyncRequest=allSelected.value ? {device_ids:ids,all:true,filter:filter()} : {device_ids:ids,texts:[...new Set(selected.value)]};
  const targetNames=ids.map(id=>`${deviceName(id)} · ${id.slice(-8)}`).join('、');
  const needsUpgrade=devices.value.some(d=>ids.includes(d.device_id) && !d.additions_supported);
  busy.value=true;error.value='';notice.value='';
  try {
    const result=await dictionaryApi.sync(request);if(!alive) return;
    notice.value=`已处理 ${result.words} 个词与读音，新增 ${result.queued} 条投递，跳过 ${result.skipped} 个不支持的词。目标：${targetNames}。仅增量添加，不删除手机已有词；等待手机确认，排队不代表候选已生效。${needsUpgrade ? '部分目标手机需升级后才能接收。' : ''}`;
    await load();
  } catch(e) {if(alive) error.value=(e as Error).message;} finally {if(alive) busy.value=false;}
}
async function bind(d:DictionaryDevice) {
  if(busy.value || !canManage.value || d.restore_enabled === false || !(await askConfirmation(`将「${label(d)} · ${d.device_id.slice(-8)}」所属的个人词库与当前词库合并？其已绑定手机也会一起加入，原始上报来源保留，已有停用/删除规则优先。请确认都是你要共享词库的手机。`, { title: '确认合并个人词库', confirmText: '确认合并' }))) return;
  if (!alive || busy.value || !canManage.value) return;
  busy.value=true; error.value=''; notice.value='';
  try {await dictionaryApi.bind(d.device_id);if(!alive) return;notice.value='后台绑定已保存，等待手机联网同步。';clearSelection();await load(1);}
  catch(e) {if(alive) error.value=(e as Error).message;} finally {if(alive) busy.value=false;}
}
async function decide(texts:string[],value:DictionaryStatus) {
  if(busy.value || loading.value || !loaded.value || !canManage.value || !texts.length) return;
  if(value!=='enabled' && !(await askConfirmation(`${statuses[value]}这 ${texts.length} 个词的个人学习与加权？绑定手机同步后生效，原始上报明细保留；不会屏蔽公共词库中的同名词。`, { title: value === 'deleted' ? '确认删除个人词语' : '确认停用个人词语', confirmText: value === 'deleted' ? '确认删除' : '确认停用' }))) return;
  if (!alive || busy.value || !canManage.value) return;
  busy.value=true;error.value='';notice.value='';
  try {await dictionaryApi.decisions([...new Set(texts)],value);if(!alive) return;notice.value='决策已保存，等待手机确认应用。';clearSelection();await load();}
  catch(e) {if(alive) error.value=(e as Error).message;} finally {if(alive) busy.value=false;}
}
onMounted(()=>load(1)); onBeforeUnmount(()=>{alive=false;generation++;});
</script>
<template>
  <div class="content-library dictionary-page">
    <header class="library-intro"><div><span class="eyebrow">PERSONAL DICTIONARY</span><h2>个人词库与换机同步</h2><p>添加想打的词与拼音，增量同步到指定手机。本站、线上和手机已有词取并集，不因某端词少而删除手机词语。</p></div></header>
    <p v-if="error" class="library-notice error" role="alert">{{ error }} <button class="library-button small" :disabled="busy||loading" @click="load()">刷新列表</button></p>
    <p v-if="notice" class="library-notice success" role="status">{{ notice }}</p>
    <section class="library-panel">
      <h3>添加词语与拼音</h3><p>逐字填写读音，用空格分隔，例如“泰鲮 / tai ling”。后台添加不伪造选词次数；添加后需选择手机并同步，最终以手机候选实际出现为准。</p>
      <form data-testid="add-form" class="library-row" @submit.prevent="addWord">
        <input v-model="newWord" data-testid="new-word" class="library-input" aria-label="新增词语" placeholder="词语，如：泰鲮" required :disabled="busy">
        <input v-model="newPinyin" data-testid="new-pinyin" class="library-input" aria-label="新增词语拼音" placeholder="拼音，如：tai ling" required :disabled="busy" autocapitalize="off" spellcheck="false">
        <button class="library-button primary" :disabled="busy||loading">添加词语</button>
      </form>
    </section>
    <section class="library-panel">
      <h3>选择接收词语的手机</h3><p>可选多台手机，不需要先绑定。新手机注册个人词库同步后才会出现；旧版本可以排队，但须升级才能接收增量词语。系统词典不等于其他输入法的私有词库。</p>
      <div class="device-grid">
        <article v-for="d in devices" :key="d.device_id" class="device-card" :class="{chosen:targets.includes(d.device_id)}">
          <label class="target-label"><input type="checkbox" :data-testid="`target-${d.device_id}`" :checked="targets.includes(d.device_id)" :disabled="busy||loading" @change="toggleTarget(d.device_id,($event.target as HTMLInputElement).checked)"><strong>{{ label(d) }}</strong></label>
          <small>{{ [d.brand,d.model].filter(Boolean).join(' ') }}</small><small>{{ d.device_id }}</small>
          <p>{{ d.additions_supported ? '支持增量接收' : '需升级：尚未支持增量接收' }}<br>待应用：{{ d.additions_pending ?? 0 }} 条<br>增量应用确认：{{ date(d.additions_applied_at) }}</p>
          <details><summary>原始备份与管理状态</summary>
            <p>{{ d.restore_enabled === false ? '仅备份：本站不下发管理决策，但可增量添加词语' : d.in_group ? (d.synced ? '手机已确认应用管理决策' : '等待手机同步') : '未绑定此词库' }}</p>
            <p>最近上报：{{ date(d.last_report_at) }}<br>管理应用确认：{{ date(d.applied_at) }}</p>
            <p>系统词典：{{ migration[d.migration_status] || d.migration_status }} · 导入 {{ d.imported }} 词</p>
            <button v-if="d.in_group" class="library-button small" :data-testid="`device-${d.device_id}`" :disabled="busy||loading" @click="filterDevice(d.device_id)">查看此手机明细</button>
            <button v-else class="library-button small" :data-testid="`bind-${d.device_id}`" :disabled="busy||loading||!canManage||d.restore_enabled===false" @click="bind(d)">绑定共享词库（非单次同步）</button>
          </details>
        </article>
      </div>
      <p v-if="!devices.length">{{ loading ? '正在加载手机…' : '暂无已注册手机，暂时不能下发。' }}</p>
    </section>
    <section class="library-panel">
      <p v-if="devices.length && !canManage" class="library-notice">本站为这些手机的词库备份端，支持新增与增量同步；停用、删除和换机绑定仍请在主后台操作。</p>
      <h3>{{ view==='merged' ? '合并后的个人词库' : deviceId ? deviceName(deviceId)+'的上报明细' : '共享个人词库 · 各来源明细' }}</h3>
      <p>同词多种编码或来源不等于重复加权。真实选词次数仅来自点击记录，非点击记录显示“—”；合并权重不能相加当作次数。“未记录”读音的词将跳过下发。</p>
      <form class="library-row" @submit.prevent="load(1)">
        <button class="library-button" type="button" :disabled="busy||loading" @click="filterDevice('')">全部来源（含后台添加）</button>
        <input v-model="q" data-testid="search" class="library-input" aria-label="搜索个人词语" placeholder="搜索词语或拼音" :disabled="busy">
        <select v-model="view" class="library-input" data-testid="view-filter" aria-label="词库视图" :disabled="busy"><option value="merged">按词合并查看</option><option value="raw">各手机原始上报</option></select>
        <select v-model="status" class="library-input" data-testid="status-filter" aria-label="个人词语状态" :disabled="busy"><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option><option value="deleted">已删除</option></select>
        <button class="library-button" :disabled="busy||loading">查询</button>
      </form>
      <div class="batch-actions">
        <button class="library-button small" data-testid="select-page" :disabled="busy||loading||!loaded||!rows.length" @click="selectPage">全选当前页</button>
        <button class="library-button small" data-testid="select-all" :disabled="busy||loading||!loaded||!total" @click="selectAll">选择全部筛选结果</button>
        <button class="library-button small" data-testid="select-none" :disabled="busy||loading||!hasSelection" @click="clearSelection">全不选</button>
        <span>{{ allSelected ? `已选全部筛选结果${totalWords===undefined ? '（按词去重）' : ` · ${totalWords} 词`}` : `已选 ${selected.length} 词` }}</span>
        <button class="library-button primary" data-testid="sync-selected" :disabled="busy||loading||!loaded||!hasSelection||!targets.length" @click="syncSelected">增量同步到所选 {{ targets.length }} 台手机</button>
      </div>
      <p v-if="allSelected">已选择全部匹配页，翻页保留，改变筛选会清除选择。仅同步启用且读音可靠的词；如需逐词取消或批量管理，请先全不选。</p>
      <div class="batch-actions"><span>管理决策（仅主后台）</span><button v-for="s in (['enabled','disabled','deleted'] as const)" :key="s" class="library-button small" :disabled="busy||loading||!loaded||!canManage||allSelected||!selected.length" @click="decide(selected,s)">批量{{ statuses[s] }}</button></div>
      <div class="table-scroll"><table><thead><tr><th>选择</th><th>词语</th><th>状态</th><th>手机</th><th>来源</th><th>拼音</th><th>输入码</th><th>真实选词次数</th><th>原始权重</th><th>最近使用</th><th>管理</th></tr></thead>
        <tbody><tr v-for="(r,i) in rows" :key="[r.device_id,r.text,r.kind,r.code,r.pinyin,r.source].join('|')">
          <td><input type="checkbox" :data-testid="`select-${i}`" :checked="allSelected||selected.includes(r.text)" :aria-label="`选择${r.text}`" :disabled="busy||loading||!loaded||allSelected" @change="toggleWord(r.text,($event.target as HTMLInputElement).checked)"></td>
          <td><strong>{{ r.text }}</strong></td><td>{{ statuses[r.status] }}</td>
          <td>{{ rowDevice(r) }}</td>
          <td>{{ source(r) }}</td><td>{{ r.pinyin || '未记录' }}</td><td>{{ r.code || '—' }}</td>
          <td>{{ hasChoices(r) ? r.count : '—（非点击记录）' }}</td>
          <td>{{ r.kind==='merged' ? '按各编码分别生效' : r.kind==='choice' ? r.weight.toFixed(2) : '—' }}</td>
          <td>{{ r.last_used ? new Date(r.last_used).toLocaleString('zh-CN') : '无使用时间' }}</td>
          <td><div class="row-actions"><button class="library-button small" :disabled="busy||loading||!loaded||!canManage" @click="decide([r.text],'enabled')">保留/恢复</button><button class="library-button small" :disabled="busy||loading||!loaded||!canManage" @click="decide([r.text],'disabled')">停用</button><button class="library-button small danger" :data-testid="`delete-${i}`" :disabled="busy||loading||!loaded||!canManage" @click="decide([r.text],'deleted')">删除</button></div></td>
        </tr></tbody></table></div>
      <p v-if="!rows.length">{{ loading ? '正在加载…' : '尚无匹配的词条' }}</p>
      <footer class="batch-actions"><span>共 {{ total }} {{ view==='merged' ? '词' : '条来源记录' }} · {{ page }}/{{ pageCount }} 页</span><button class="library-button" :disabled="busy||loading||page<=1" @click="load(page-1)">上一页</button><button class="library-button" data-testid="next-page" :disabled="busy||loading||page>=pageCount" @click="load(page+1)">下一页</button><button class="library-button" :disabled="busy||loading" @click="load()">刷新同步状态</button></footer>
    </section>
  </div>
</template>
<style scoped>
.dictionary-page .library-row{flex-wrap:wrap}
.device-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:12px}.device-card{padding:16px;background:#f7f9fc;border:1px solid #e0e5f0;border-radius:12px}.device-card.chosen{border-color:var(--lib-accent);background:#f3f4ff}.target-label{display:flex;gap:10px;align-items:center}.device-card small{display:block;overflow-wrap:anywhere;color:#64748b}.dictionary-page p{line-height:1.7;color:#64748b}.batch-actions,.row-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.batch-actions{margin:16px 0}.row-actions{flex-wrap:nowrap}.table-scroll{overflow-x:auto}table{width:100%;border-collapse:collapse;white-space:nowrap}td,th{padding:12px;text-align:left;border-bottom:1px solid #e2e8f0}button:disabled{cursor:default;opacity:.5}.library-row .library-input{width:auto;min-width:150px;flex:1}.dictionary-page section+section{margin-top:20px}.dictionary-page summary{cursor:pointer;font-size:13px;color:#64748b}
</style>
