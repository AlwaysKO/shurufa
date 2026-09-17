<script setup lang="ts">
import { useConfirmation } from '../confirmation';
import { computed, onMounted, onBeforeUnmount, ref } from 'vue';
import { dictionaryApi, type DictionaryDevice, type DictionaryEntry, type DictionaryStatus } from '../api/personalDictionary';
import './content-library.css';
const askConfirmation = useConfirmation();

const devices = ref<DictionaryDevice[]>([]), rows = ref<DictionaryEntry[]>([]);
const total = ref(0), page = ref(1), deviceId = ref(''), q = ref(''), status = ref('');
const view = ref('raw');
const selected = ref<string[]>([]), busy = ref(false), loading = ref(false), error = ref(''), notice = ref('');
let generation = 0;
const canManage = computed(() => devices.value.some(d => d.in_group) && devices.value.filter(d => d.in_group).every(d => d.restore_enabled !== false));
const pageCount = computed(() => Math.max(1,Math.ceil(total.value/50)));
const label = (d:DictionaryDevice) => d.dashboard_name || [d.brand,d.model].filter(Boolean).join(' ') || d.name || d.device_id;
const deviceName = (id:string) => devices.value.find(d => d.device_id===id) ? label(devices.value.find(d => d.device_id===id)!) : id;
const date = (s:string|null) => s ? new Date(s).toLocaleString('zh-CN') : '尚未确认';
const statuses = {enabled:'启用',disabled:'停用',deleted:'已删除'};
const migration: Record<string,string> = {complete:'读取完成',permission_denied:'没有读取权限',unavailable:'系统词典不可用',failed:'读取失败',not_attempted:'尚未尝试'};
async function load(targetPage=page.value) {
  const version=++generation; loading.value=true; error.value=''; selected.value=[];
  try {
    const [directory,result]=await Promise.all([dictionaryApi.devices(),dictionaryApi.entries({device_id:deviceId.value,q:q.value.trim(),status:status.value,page:targetPage,view:view.value})]);
    if(version!==generation) return;
    devices.value=directory.devices; rows.value=result.entries; total.value=result.total; page.value=targetPage;
  } catch(e) { if(version===generation) error.value=(e as Error).message; }
  finally {if(version===generation) loading.value=false;}
}
function filterDevice(id:string) {deviceId.value=id;view.value='raw';void load(1);}
async function bind(d:DictionaryDevice) {
  if(busy.value || !canManage.value || d.restore_enabled === false || !(await askConfirmation(`将「${label(d)} · ${d.device_id.slice(-8)}」所属的个人词库与当前词库合并？其已绑定手机也会一起加入，原始上报来源保留，已有停用/删除规则优先。请确认都是你要共享词库的手机。`, { title: '确认合并个人词库', confirmText: '确认合并' }))) return;
  if (busy.value || !canManage.value) return;
  busy.value=true; error.value=''; notice.value='';
  try {await dictionaryApi.bind(d.device_id);notice.value='后台绑定已保存，等待手机联网同步。';await load(1);}
  catch(e) {error.value=(e as Error).message;} finally {busy.value=false;}
}
async function decide(texts:string[],value:DictionaryStatus) {
  if(busy.value || !canManage.value || !texts.length) return;
  if(value!=='enabled' && !(await askConfirmation(`${statuses[value]}这 ${texts.length} 个词的个人学习与加权？绑定手机同步后生效，原始上报明细保留；不会屏蔽公共词库中的同名词。`, { title: value === 'deleted' ? '确认删除个人词语' : '确认停用个人词语', confirmText: value === 'deleted' ? '确认删除' : '确认停用' }))) return;
  if (busy.value || !canManage.value) return;
  busy.value=true;error.value='';notice.value='';
  try {await dictionaryApi.decisions([...new Set(texts)],value);notice.value='决策已保存，等待手机确认应用。';await load();}
  catch(e) {error.value=(e as Error).message;} finally {busy.value=false;}
}
onMounted(()=>load(1)); onBeforeUnmount(()=>{generation++;});
</script>
<template>
  <div class="content-library dictionary-page">
    <header class="library-intro"><div><span class="eyebrow">PERSONAL DICTIONARY</span><h2>个人词库与换机同步</h2><p>由后台绑定手机、审核个人词语。设备原始明细分别保留，不与公共词库或聊天数据混合。</p></div></header>
    <p v-if="error" class="library-notice error" role="alert">{{ error }} <button @click="load()">重试</button></p>
    <p v-if="notice" class="library-notice success" role="status">{{ notice }}</p>
    <section class="library-panel">
      <h3>手机与同步状态</h3><p>仅显示已升级并注册个人词库同步的手机。新手机首次联网、允许上报后才会出现；系统词典不等于其他输入法的私有词库。</p>
      <div class="device-grid">
        <article v-for="d in devices" :key="d.device_id" class="device-card">
          <strong>{{ label(d) }}</strong><small>{{ [d.brand,d.model].filter(Boolean).join(' ') }}</small><small>{{ d.device_id }}</small>
          <p>{{ d.restore_enabled === false ? '仅备份：本站收到上报，不向手机下发管理决策' : d.in_group ? (d.synced ? '手机已确认应用' : '等待手机同步') : '未绑定此词库' }}</p>
          <p>最近上报：{{ date(d.last_report_at) }}<br>应用确认：{{ date(d.applied_at) }}</p>
          <p>系统词典：{{ migration[d.migration_status] || d.migration_status }} · 导入 {{ d.imported }} 词</p>
          <button v-if="d.in_group" :data-testid="`device-${d.device_id}`" :disabled="busy||loading" @click="filterDevice(d.device_id)">查看此手机明细</button>
          <button v-else :data-testid="`bind-${d.device_id}`" :disabled="busy||loading||!canManage||d.restore_enabled===false" @click="bind(d)">绑定到当前个人词库</button>
        </article>
      </div>
    </section>
    <section class="library-panel">
      <p v-if="devices.length && !canManage" class="library-notice">本站为这些手机的词库备份端，可以查看各自上报；保留、删除和换机绑定请在主后台操作。通过左上角切换手机可查看其他手机的独立备份。</p>
      <h3>{{ view==='merged' ? '合并后的个人词库' : deviceId ? deviceName(deviceId)+'的上报明细' : '共享个人词库 · 各来源明细' }}</h3>
      <p>同一个词可有多台手机、多种编码的记录，不等于重复加权。次数只指真实选词；导入词不伪造次数。“未记录”读音的词可能无法直接召回。</p>
      <form class="library-row" @submit.prevent="load(1)">
        <button type="button" :disabled="busy||loading" @click="filterDevice('')">全部绑定手机</button>
        <input v-model="q" class="library-input" aria-label="搜索个人词语" placeholder="搜索词语或拼音">
        <select v-model="view" aria-label="词库视图"><option value="raw">各手机原始上报</option><option value="merged">按词合并查看</option></select>
        <select v-model="status" aria-label="个人词语状态"><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option><option value="deleted">已删除</option></select>
        <button :disabled="busy||loading">查询</button>
      </form>
      <div class="batch-actions"><span>已选 {{ selected.length }} 词</span><button v-for="s in (['enabled','disabled','deleted'] as const)" :key="s" :disabled="busy||loading||!canManage||!selected.length" @click="decide(selected,s)">批量{{ statuses[s] }}</button></div>
      <div class="table-scroll"><table><thead><tr><th>选择</th><th>词语/状态</th><th>手机/来源</th><th>拼音/输入码</th><th>次数/原始权重</th><th>管理</th></tr></thead>
        <tbody><tr v-for="(r,i) in rows" :key="[r.device_id,r.text,r.kind,r.code,r.pinyin,r.source].join('|')">
          <td><input v-model="selected" type="checkbox" :value="r.text" :aria-label="`选择${r.text}`" :disabled="busy||loading"></td>
          <td><strong>{{ r.text }}</strong><br>{{ statuses[r.status] }}</td>
          <td>{{ r.device_ids ? r.device_ids.map(deviceName).join('、') : deviceName(r.device_id) }}<br>{{ r.kind==='merged' ? '多个来源（切回明细可查看）' : r.source==='system_dictionary' ? '系统词典导入' : r.kind==='choice' ? '实际选词学习' : '选词读音' }}</td>
          <td>{{ r.pinyin || '未记录' }}<br>{{ r.code || '—' }}</td>
          <td>{{ r.kind!=='word' ? r.count : '—（非点击记录）' }}<br>{{ r.kind==='merged' ? '按各编码分别生效' : r.kind==='choice' ? r.weight.toFixed(2) : '—' }}<br><small>{{ r.last_used ? new Date(r.last_used).toLocaleString('zh-CN') : '无使用时间' }}</small></td>
          <td><button :disabled="busy||loading||!canManage" @click="decide([r.text],'enabled')">保留/恢复</button><button :disabled="busy||loading||!canManage" @click="decide([r.text],'disabled')">停用</button><button :data-testid="`delete-${i}`" :disabled="busy||loading||!canManage" @click="decide([r.text],'deleted')">删除</button></td>
        </tr></tbody></table></div>
      <p v-if="!rows.length">{{ loading ? '正在加载…' : '尚无匹配的上报词条' }}</p>
      <footer class="batch-actions"><span>共 {{ total }} 条来源记录 · {{ page }}/{{ pageCount }} 页</span><button :disabled="busy||loading||page<=1" @click="load(page-1)">上一页</button><button data-testid="next-page" :disabled="busy||loading||page>=pageCount" @click="load(page+1)">下一页</button><button :disabled="busy||loading" @click="load()">刷新同步状态</button></footer>
    </section>
  </div>
</template>
<style scoped>
.device-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:12px}.device-card{padding:16px;background:#f7f9fc;border-radius:12px}.device-card small{display:block;overflow-wrap:anywhere;color:#64748b}.dictionary-page p{line-height:1.7;color:#64748b}.batch-actions{display:flex;gap:10px;align-items:center;margin:16px 0;flex-wrap:wrap}.table-scroll{overflow-x:auto}table{width:100%;border-collapse:collapse;white-space:nowrap}td,th{padding:12px;text-align:left;border-bottom:1px solid #e2e8f0}button{cursor:pointer;padding:7px 10px;margin-right:4px}button:disabled{cursor:default;opacity:.5}
</style>
