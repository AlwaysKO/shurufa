<script setup lang="ts">
import { useConfirmation } from '../confirmation';
import { computed, onMounted, ref, watch } from 'vue';
import { api, scopedAssetUrl, type LibrarySticker, type StickerLibrary, type StickerKeywordGroup } from '../api';
import './content-library.css';

const askConfirmation = useConfirmation();

const library = ref<StickerLibrary>({ groups: [], systemCount: 0, personalCount: 0, warnings: [] });
const loading = ref(false);
const loaded = ref(false);
const loadError = ref('');
const busy = ref(false);
const msg = ref('');
const err = ref('');
const q = ref('');
const filter = ref('all');
const selectedKeyword = ref('');
const newKeyword = ref('');
const fileInput = ref<HTMLInputElement | null>(null);
const uploadTarget = ref<{ keyword: string; keywords: string } | null>(null);
const editingId = ref<number | null>(null);
const editingKeywords = ref('');
const failedImages = ref(new Set<string>());
const groupsWithImages = computed(() => library.value.groups.filter(g => g.assets.length).length);
const PAGE_SIZE = 10;
const currentPage = ref(1);
const pageInput = ref('');
const pageError = ref('');
const filteredGroups = computed(() => library.value.groups.filter(g =>
  (g.keyword.includes(q.value.trim()) || g.aliases.some(alias => alias.includes(q.value.trim()))) && (filter.value === 'all' || (filter.value === 'filled' ? g.assets.length > 0 : !g.assets.length))));
const pageCount = computed(() => Math.max(1, Math.ceil(filteredGroups.value.length / PAGE_SIZE)));
const pageStart = computed(() => (currentPage.value - 1) * PAGE_SIZE);
const pagedGroups = computed(() => filteredGroups.value.slice(pageStart.value, pageStart.value + PAGE_SIZE));
const activeGroup = computed(() => pagedGroups.value.find(g => g.keyword === selectedKeyword.value) ?? pagedGroups.value[0]);
const orderDraft = ref<string[] | null>(null);
const dragging = ref<string | null>(null);
const editingAliases = ref(false);
const aliasDraft = ref<string[]>([]);
const assetKey = (asset: LibrarySticker) => `${asset.source}:${asset.id}`;
const orderedAssets = computed(() => {
  const assets = activeGroup.value?.assets ?? [];
  if (!orderDraft.value) return assets;
  const byKey = new Map(assets.map(asset => [assetKey(asset), asset]));
  const known = new Set(orderDraft.value);
  return [...orderDraft.value.map(key => byKey.get(key)).filter((asset): asset is LibrarySticker => !!asset),
    ...assets.filter(asset => !known.has(assetKey(asset)))];
});
const orderDirty = computed(() => orderDraft.value !== null &&
  JSON.stringify(orderedAssets.value.map(assetKey)) !== JSON.stringify(activeGroup.value?.assets.map(assetKey)));
watch(() => activeGroup.value?.keyword, () => {
  orderDraft.value = null; dragging.value = null; editingAliases.value = false; aliasDraft.value = [];
});
function replaceGroup(group: StickerKeywordGroup) {
  const index = library.value.groups.findIndex(item => item.keyword === group.keyword);
  if (index >= 0) library.value.groups[index] = group;
}
function moveAsset(from: string, to: string) {
  if (busy.value || loading.value || from === to) return;
  const keys = orderedAssets.value.map(assetKey), start = keys.indexOf(from), end = keys.indexOf(to);
  if (start < 0 || end < 0) return;
  keys.splice(start, 1); keys.splice(end, 0, from); orderDraft.value = keys;
}
function dragStart(event: DragEvent, asset: LibrarySticker) {
  if (busy.value || loading.value) { event.preventDefault(); return; }
  dragging.value = assetKey(asset);
  if (event.dataTransfer) { event.dataTransfer.setData('text/plain', dragging.value); event.dataTransfer.effectAllowed = 'move'; }
}
function dropAsset(asset: LibrarySticker) {
  if (dragging.value) moveAsset(dragging.value, assetKey(asset));
  dragging.value = null;
}
async function saveOrder() {
  if (busy.value || loading.value || !activeGroup.value || !orderDirty.value) return;
  const keyword = activeGroup.value.keyword, assetOrder = orderedAssets.value.map(assetKey);
  busy.value = true; err.value = ''; msg.value = '';
  try {
    const { group } = await api.updateStickerGroup(keyword, { assetOrder });
    replaceGroup(group);
    if (activeGroup.value?.keyword === keyword) orderDraft.value = null;
    msg.value = '图片顺序已保存；手机下次打开键盘检查更新后生效。';
  } catch (error) { err.value = `排序保存失败：${(error as Error).message}`; }
  finally { busy.value = false; }
}
function startAliases() {
  if (busy.value || loading.value || !activeGroup.value) return;
  aliasDraft.value = [...activeGroup.value.aliases]; editingAliases.value = true; err.value = '';
}
async function saveAliases() {
  if (busy.value || loading.value || !activeGroup.value) return;
  const keyword = activeGroup.value.keyword, aliases = [...aliasDraft.value];
  busy.value = true; err.value = ''; msg.value = '';
  try {
    const { group } = await api.updateStickerGroup(keyword, { aliases });
    replaceGroup(group);
    if (activeGroup.value?.keyword === keyword) editingAliases.value = false;
    msg.value = '同组说法已保存并纳入手机同步；自动推荐只匹配完整说法。';
  } catch (error) { err.value = `说法保存失败：${(error as Error).message}`; }
  finally { busy.value = false; }
}
watch([q, filter], () => { currentPage.value = 1; pageInput.value = ''; pageError.value = ''; editingId.value = null; }, { flush: 'sync' });
watch(pageCount, count => { if (currentPage.value > count) currentPage.value = count; }, { flush: 'sync' });
function goToPage(page: number) {
  currentPage.value = Math.max(1, Math.min(pageCount.value, page));
  pageInput.value = ''; pageError.value = ''; editingId.value = null;
}
function jumpToPage() {
  const value = pageInput.value.trim();
  const page = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(page) || page < 1 || page > pageCount.value) {
    pageError.value = `请输入 1～${pageCount.value} 的整数页码`; return;
  }
  goToPage(page);
}
function revealKeyword(keyword: string) {
  q.value = ''; filter.value = 'all';
  const index = library.value.groups.findIndex(g => g.keyword === keyword);
  goToPage(index < 0 ? 1 : Math.floor(index / PAGE_SIZE) + 1);
  selectedKeyword.value = library.value.groups[index]?.keyword ?? keyword;
}

async function load() {
  loading.value = true; loadError.value = '';
  try { library.value = await api.stickerLibrary(); loaded.value = true; }
  catch (e) { loadError.value = `词库加载失败：${(e as Error).message}`; }
  finally { loading.value = false; }
}
async function addKeyword() {
  if (busy.value) return;
  const keyword = newKeyword.value.trim();
  if (!keyword || keyword.length > 100 || /[,，\r\n]/.test(keyword)) { err.value = '请输入单个关键词（1～100 字，不含逗号或换行）'; return; }
  const existing = library.value.groups.find(g => g.keyword === keyword || g.aliases.includes(keyword));
  if (existing) { revealKeyword(existing.keyword); msg.value = `“${keyword}”属于“${existing.keyword}”组，已为你打开，可直接上传图片。`; err.value = ''; newKeyword.value = ''; return; }
  busy.value = true; msg.value = ''; err.value = '';
  try {
    const saved = await api.addStickerKeyword(keyword);
    // 独立创建空组，无需图片；更新本地状态避免成功后刷新失败造成重复提交。
    if (!library.value.groups.some(group => group.keyword === saved.keyword)) library.value.groups.unshift({ keyword: saved.keyword, aliases: [saved.keyword], confirmedAliases: [], category: '自定义', planned: false, custom: true, assets: [] });
    await load();
    revealKeyword(saved.keyword); newKeyword.value = '';
    msg.value = saved.keyword === keyword ? `已新增关键词“${keyword}”，可以现在上传，也可以稍后补图。` : `已打开“${saved.keyword}”组；相同说法不重复建组。`;
  } catch (e) { err.value = `新增失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
function readImageSize(file: File): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file); const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolve({ width: img.naturalWidth, height: img.naturalHeight }); };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('无法读取图片，请检查文件是否损坏')); };
    img.src = url;
  });
}
function chooseUpload() {
  if (busy.value || !activeGroup.value) return;
  uploadTarget.value = { keyword: activeGroup.value.keyword, keywords: activeGroup.value.aliases.join(',') };
  fileInput.value?.click();
}
async function uploadFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || busy.value) return;
  // 文件选择前锁定词；即使选择文件期间切换搜索/分组也不会错传。
  const target = uploadTarget.value ?? (activeGroup.value ? { keyword: activeGroup.value.keyword, keywords: activeGroup.value.aliases.join(',') } : null);
  uploadTarget.value = null;
  if (!target) return;
  const { keyword, keywords } = target;
  err.value = ''; msg.value = '';
  if (!/\.(gif|png|jpe?g|webp)$/i.test(file.name) || !file.size || file.size > 5 * 1024 * 1024) {
    err.value = '请选择 GIF / PNG / JPG / WebP 图片，单张不超过 5 MB，不能是空文件。'; input.value = ''; return;
  }
  busy.value = true;
  try {
    const { width, height } = await readImageSize(file);
    const bytes = new Uint8Array(await file.arrayBuffer()); let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    await api.uploadSticker({ file_base64: btoa(binary), filename: file.name, keywords, group_keyword: keyword, width, height });
    msg.value = `已上传到“${keyword}”组 · ${width} × ${height}，同组说法共用这张表情。`;
    await load();
    revealKeyword(keyword);
  } catch (e) { err.value = `上传失败：${(e as Error).message}`; }
  finally { input.value = ''; busy.value = false; }
}
function selectKeyword(keyword: string) { selectedKeyword.value = keyword; editingId.value = null; }
async function removeGroup() {
  if (busy.value || loading.value || !activeGroup.value) return;
  const group = activeGroup.value;
  const payload = {confirm:'DELETE' as const, aliases:[...group.aliases], assetKeys:group.assets.map(assetKey)};
  if (!(await askConfirmation(`删除关键词组“${group.keyword}”？将一并删除 ${group.aliases.length} 种说法和 ${group.assets.length} 张图片，对所有设备生效。其他组共用的图片会保留，此操作不可撤销。`))) return;
  if (busy.value || loading.value) return;
  busy.value = true; err.value = ''; msg.value = '';
  try {
    const result = await api.deleteStickerGroup(group.keyword, payload);
    library.value.groups = library.value.groups.filter(item => item.keyword !== group.keyword);
    msg.value = `已删除“${group.keyword}”及其说法和图片。${result.files_pending ? '部分图片文件正在后台重试清理。' : ''}`;
    await load();
  } catch (error) { err.value = `关键词删除失败：${(error as Error).message}`; }
  finally { busy.value = false; }
}
function startEdit(asset: LibrarySticker) { editingId.value = Number(asset.id); editingKeywords.value = asset.keywords.join('，'); err.value = ''; }
async function saveEdit(asset: LibrarySticker) {
  if (busy.value) return;
  const keywords = editingKeywords.value.split(/[,，]/).map(s => s.trim()).filter(Boolean).join(',');
  if (!keywords) { err.value = '至少保留一个关键词'; return; }
  busy.value = true; msg.value = ''; err.value = '';
  try { await api.updateStickerKeywords(Number(asset.id), keywords); editingId.value = null; msg.value = '图片关键词已更新'; await load(); }
  catch (e) { err.value = `保存失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
async function remove(asset: LibrarySticker) {
  if (busy.value || !(await askConfirmation('删除这张表情？它将从公共图库的所有关联关键词和推荐结果中移除，关键词保留。'))) return;
  if (busy.value || loading.value) return;
  busy.value = true; msg.value = ''; err.value = '';
  try { if (asset.source === 'system') await api.deleteSystemSticker(String(asset.id)); else await api.deleteSticker(Number(asset.id)); msg.value = '图片已删除，关键词已保留'; await load(); }
  catch (e) { err.value = `删除失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
function imageFailed(asset: LibrarySticker) { failedImages.value.add(`${asset.source}:${asset.id}`); }
onMounted(load);
</script>

<template>
  <div class="content-library sticker-page">
    <header class="library-intro">
      <div class="intro-mark sticker-mark" aria-hidden="true">☺</div>
      <div><span class="eyebrow">EXPRESSION LIBRARY</span><h2>多种说法，共用一组表情</h2><p>所有设备共享关键词和推荐图。按意思归组，同组说法共用表情，不必重复上传。</p></div>
    </header>
    <div class="library-stats" aria-label="表情库统计">
      <div class="library-stat"><span>全部语义组</span><strong>{{ loaded ? library.groups.length : '—' }}</strong></div>
      <div class="library-stat"><span>已有表情的组</span><strong>{{ loaded ? groupsWithImages : '—' }}</strong></div>
      <div class="library-stat"><span>系统表情</span><strong>{{ loaded ? library.systemCount : '—' }}</strong></div>
      <div class="library-stat"><span>公共上传</span><strong>{{ loaded ? library.personalCount : '—' }}</strong></div>
    </div>
    <section class="library-panel compose-panel">
      <div class="section-heading"><div><h3>新增关键词</h3><p>只建词，不必同时上传图片。已有关键词请在下方选中后直接上传。</p></div><span class="library-badge">支持空关键词分组</span></div>
      <form class="library-row" @submit.prevent="addKeyword">
        <input v-model="newKeyword" data-testid="new-keyword" class="library-input" aria-label="新关键词" maxlength="100" placeholder="输入一个新关键词，例如：开饭啦" />
        <button data-testid="add-keyword" class="library-button primary" :disabled="busy || loading || !loaded || !newKeyword.trim()" type="button" @click="addKeyword">＋ 新增关键词</button>
      </form>
    </section>
    <p v-if="msg" class="library-notice success" role="status">{{ msg }}</p>
    <p v-if="err" class="library-notice error" role="alert">{{ err }}</p>
    <div v-if="loadError" class="library-notice error" role="alert">{{ loadError }}<button data-testid="retry-library" class="text-button" :disabled="loading" @click="load">重新加载</button></div>
    <p v-for="warning in library.warnings" :key="warning" class="library-notice warning" role="status">{{ warning }}</p>
    <div v-if="loading && !loaded" class="library-panel library-empty" role="status">正在整理关键词与表情…</div>
    <div v-if="loaded" class="keyword-layout">
      <aside class="library-panel keyword-sidebar" aria-label="关键词库">
        <h3>关键词库 <span class="library-badge">{{ library.groups.length }}</span></h3>
        <input v-model="q" class="library-input" type="search" aria-label="搜索关键词" placeholder="搜索组名或同义说法…" />
        <div class="library-chips" aria-label="图片状态筛选">
          <button v-for="item in [{ id: 'all', label: '全部' }, { id: 'filled', label: '有表情' }, { id: 'empty', label: '待补图' }]" :key="item.id" :class="{ active: filter === item.id }" :aria-pressed="filter === item.id" @click="filter = item.id">{{ item.label }}</button>
        </div>
        <nav class="keyword-list" aria-label="选择关键词">
          <button v-for="group in pagedGroups" :key="group.keyword" :data-testid="`keyword-${group.keyword}`" class="keyword-option" :class="{ active: activeGroup?.keyword === group.keyword }" :aria-current="activeGroup?.keyword === group.keyword ? 'true' : undefined" @click="selectKeyword(group.keyword)"><span class="keyword-label">{{ group.keyword }}<em v-if="group.aliases.length > 1">{{ group.aliases.length }} 种说法共用</em></span><small>{{ group.assets.length ? `${group.assets.length} 张` : '待补图' }}</small></button>
          <p v-if="!filteredGroups.length" class="sidebar-footer">没有匹配的关键词</p>
        </nav>
        <div class="keyword-pagination" aria-label="关键词分页">
          <p class="page-summary" aria-live="polite">共 {{ filteredGroups.length }} 组 · 每页 10 组<br />第 {{ filteredGroups.length ? currentPage : 0 }} / {{ filteredGroups.length ? pageCount : 0 }} 页<span v-if="filteredGroups.length"> · {{ pageStart + 1 }}–{{ Math.min(pageStart + PAGE_SIZE, filteredGroups.length) }} 组</span></p>
          <div class="page-buttons">
            <button data-testid="first-keyword-page" :disabled="currentPage === 1 || !filteredGroups.length" @click="goToPage(1)">首页</button>
            <button data-testid="previous-keyword-page" :disabled="currentPage === 1 || !filteredGroups.length" @click="goToPage(currentPage - 1)">上一页</button>
            <button data-testid="next-keyword-page" :disabled="currentPage >= pageCount || !filteredGroups.length" @click="goToPage(currentPage + 1)">下一页</button>
            <button data-testid="last-keyword-page" :disabled="currentPage >= pageCount || !filteredGroups.length" @click="goToPage(pageCount)">尾页</button>
          </div>
          <form class="page-jump" @submit.prevent="jumpToPage">
            <label for="keyword-page-input">跳至</label><input id="keyword-page-input" v-model="pageInput" data-testid="keyword-page-input" class="library-input" inputmode="numeric" aria-label="跳转页码" :placeholder="String(currentPage)" :disabled="!filteredGroups.length" /><span>页</span>
            <button data-testid="jump-keyword-page" type="button" :disabled="!filteredGroups.length" @click="jumpToPage">跳转</button>
          </form>
          <p v-if="pageError" data-testid="keyword-page-error" class="page-error" role="alert">{{ pageError }}</p>
        </div>
      </aside>
      <section class="library-panel keyword-gallery">
        <template v-if="activeGroup">
          <div class="section-heading">
            <div><div class="keyword-heading"><h3>{{ activeGroup.keyword }}</h3><span class="library-badge">{{ activeGroup.category }}</span><span v-if="activeGroup.planned" class="library-badge">规划词</span></div><p>{{ activeGroup.assets.length }} 张表情 · 系统 {{ activeGroup.assets.filter(a => a.source === 'system').length }} / 上传 {{ activeGroup.assets.filter(a => a.source === 'personal').length }}</p></div>
            <div class="library-row">
              <button data-testid="delete-keyword-group" class="library-button danger" :disabled="busy || loading" @click="removeGroup">删除关键词</button>
              <button data-testid="group-upload-button" class="library-button primary" :disabled="busy || loading" @click="chooseUpload">{{ busy ? '处理中…' : '＋ 上传到此组' }}</button>
            </div>
          </div>
          <input ref="fileInput" data-testid="group-upload-input" class="hidden-upload" type="file" accept=".gif,.png,.jpg,.jpeg,.webp" :aria-label="`上传表情到${activeGroup.keyword}`" @change="uploadFile" @cancel="uploadTarget = null" />
          <div data-testid="group-aliases" class="semantic-aliases">
            <div class="alias-heading"><strong>同组说法</strong><span>{{ activeGroup.aliases.length }} 种说法 · 共用下方 {{ activeGroup.assets.length }} 张表情</span><button v-if="!editingAliases" data-testid="edit-group-aliases" class="text-button" :disabled="busy || loading" @click="startAliases">编辑说法</button></div>
            <div v-if="editingAliases" class="alias-editor">
              <div v-for="(_alias, index) in aliasDraft" :key="index" class="library-row">
                <input v-model="aliasDraft[index]" :data-testid="`group-alias-input-${index}`" class="library-input" :aria-label="`第 ${index + 1} 条同组说法`" maxlength="100" :disabled="busy" />
                <button :data-testid="`remove-group-alias-${index}`" class="text-button danger" :disabled="busy" @click="aliasDraft.splice(index, 1)">删除说法</button>
              </div>
              <div class="library-actions"><button data-testid="add-group-alias" class="text-button" :disabled="busy || aliasDraft.length >= 100" @click="aliasDraft.push('')">＋ 新增说法</button><button data-testid="save-group-aliases" class="library-button primary" :disabled="busy || loading" @click="saveAliases">保存说法</button><button class="text-button" :disabled="busy" @click="editingAliases = false">取消</button></div>
              <p>每条填写一个完整说法；清空后本组不再自动推荐，图片仍保留。</p>
            </div>
            <div v-else class="alias-tags"><span v-for="alias in activeGroup.aliases" :key="alias" class="library-badge">{{ alias }}</span><span v-if="!activeGroup.aliases.length">暂无匹配说法</span></div>
            <p>完整输入匹配任一说法才自动推荐，不按句中关键词匹配。增删改保存后同步手机，不修改原图。</p>
          </div>
          <div v-if="!activeGroup.assets.length" data-testid="empty-keyword" class="library-empty"><span class="empty-mark" aria-hidden="true">☺</span><strong>“{{ activeGroup.keyword }}”还没有表情</strong><p>关键词已在这里，上传一张 GIF 就能补充到这一组。</p><button class="library-button" :disabled="busy || loading" @click="chooseUpload">选择图片上传</button></div>
          <div v-if="activeGroup.assets.length" class="sticker-order-toolbar">
            <span>拖动排序手柄调整顺序，也可用前移/后移。默认上传图片在前。</span>
            <div class="library-actions"><button data-testid="save-sticker-order" class="library-button primary" :disabled="busy || loading || !orderDirty" @click="saveOrder">保存排序</button><button class="text-button" :disabled="busy || !orderDirty" @click="orderDraft = null">取消排序</button></div>
          </div>
          <div v-if="activeGroup.assets.length" class="sticker-grid">
            <article v-for="(asset, index) in orderedAssets" :key="assetKey(asset)" :data-testid="`sticker-cell-${assetKey(asset)}`" class="sticker-cell" :class="{ dragging: dragging === assetKey(asset) }" @dragover.prevent @drop.prevent="dropAsset(asset)">
              <div class="sticker-sort-actions"><button :data-testid="`drag-sticker-${assetKey(asset)}`" class="text-button drag-handle" :draggable="!busy && !loading" :disabled="busy || loading" aria-label="拖动排列图片" @dragstart="dragStart($event, asset)" @dragend="dragging = null">⠿ 排序</button><button class="text-button" :disabled="busy || loading || index === 0" aria-label="图片前移" @click="moveAsset(assetKey(asset), assetKey(orderedAssets[index - 1]!))">前移</button><button class="text-button" :disabled="busy || loading || index === orderedAssets.length - 1" aria-label="图片后移" @click="moveAsset(assetKey(asset), assetKey(orderedAssets[index + 1]!))">后移</button></div>
              <div class="sticker-preview"><span v-if="failedImages.has(`${asset.source}:${asset.id}`)" class="library-badge">图片加载失败</span><img v-else :src="scopedAssetUrl(asset.url)" :alt="asset.keywords.join('、')" loading="lazy" @error="imageFailed(asset)" /><span class="sticker-format">{{ asset.format.toUpperCase() }}</span></div>
              <div class="sticker-meta">
                <span class="library-badge" :class="{ personal: asset.source === 'personal' }">{{ asset.source === 'system' ? '系统素材' : '公共上传' }}</span>
                <template v-if="asset.source === 'personal' && editingId === Number(asset.id)"><input v-model="editingKeywords" class="library-input" aria-label="图片关键词，多个用逗号分隔" @keyup.enter="saveEdit(asset)" /><div class="library-actions"><button class="text-button" :disabled="busy" @click="saveEdit(asset)">保存</button><button class="text-button" :disabled="busy" @click="editingId = null">取消</button></div></template>
                <p v-else class="sticker-tags">{{ asset.keywords.join(' · ') }}</p>
                <small>{{ asset.width && asset.height ? `${asset.width} × ${asset.height}` : '尺寸未知' }}<template v-if="asset.source === 'personal'"> · 使用 {{ asset.useCount }} 次</template></small>
                <div v-if="editingId !== Number(asset.id)" class="library-actions"><button v-if="asset.source === 'personal'" class="text-button" :disabled="busy || loading" @click="startEdit(asset)">修改关键词</button><button :data-testid="`delete-sticker-${asset.id}`" class="text-button danger" :disabled="busy || loading" @click="remove(asset)">删除</button></div>
              </div>
            </article>
          </div>
          <p class="upload-hint">支持 GIF / PNG / JPG / WebP，单张不超过 5 MB。上传后归入“{{ activeGroup.keyword }}”语义组并使用已保存的同组说法，用于所有设备的斗图搜索与手机关键词推荐；手机下次打开键盘检查更新后补充，不修改系统素材。空关键词组不触发图片推荐，规划词不等于已启用全部语义扩展；未发布试稿不在这里展示。</p>
        </template>
        <div v-else class="library-empty"><strong>{{ q || filter !== 'all' ? '没有匹配的关键词' : '从第一个关键词开始' }}</strong><p>调整左侧筛选，或在上方新增关键词。</p></div>
      </section>
    </div>
  </div>
</template>
