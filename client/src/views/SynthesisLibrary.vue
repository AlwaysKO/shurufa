<script setup lang="ts">
import { useConfirmation } from '../confirmation';
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref } from 'vue';
import { api, scopedAssetUrl, type SynthesisAsset, type SynthesisLayout } from '../api';
import './content-library.css';

const askConfirmation = useConfirmation();
const assets = ref<SynthesisAsset[]>([]);
const loading = ref(false); const loaded = ref(false); const busy = ref(false);
const loadError = ref(''); const error = ref(''); const message = ref('');
const uploadProgress = ref<number | null>(null);
const canvasWidth = computed(() => editing.value?.width ?? 240);
const canvasHeight = computed(() => editing.value?.height ?? 240);
const name = ref(''); const sourceStatement = ref('');
const editing = ref<SynthesisAsset | null>(null);
const editor = ref<HTMLElement | null>(null);
const file = ref<File | null>(null); const fileInput = ref<HTMLInputElement | null>(null);
const preview = ref(''); const failed = ref(new Set<string>());
const selected = ref(new Set<string>());
const manageable = (asset: SynthesisAsset) => asset.deletable && asset.source === 'personal';
const manageableAssets = computed(() => assets.value.filter(manageable));
const defaultArea = { x: 6, y: 190, width: 228, height: 44 };
const defaultLayout: SynthesisLayout = { minFontSize: 12, maxFontSize: 24, textColor: '#222222', strokeColor: '#ffffff', strokeWidth: 1, alignment: 'center', maxLines: 2 };
const safeArea = reactive({ ...defaultArea });
const fields = [{ key: 'x', label: '左侧 X' }, { key: 'y', label: '顶部 Y' }, { key: 'width', label: '宽度' }, { key: 'height', label: '高度' }] as const;
function clearPreview() { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ''; }
let disposed = false;
let loadSequence = 0;
onBeforeUnmount(() => { disposed = true; clearPreview(); });
async function load() {
  const sequence = ++loadSequence;
  loading.value = true; loadError.value = '';
  try {
    const result = await api.synthesisLibrary(); if (disposed || sequence !== loadSequence) return;
    assets.value = result.assets; loaded.value = true; failed.value.clear();
    selected.value = new Set([...selected.value].filter(id => assets.value.some(a => a.id === id && manageable(a))));
  } catch (e) { if (!disposed && sequence === loadSequence) loadError.value = `底图库加载失败：${(e as Error).message}`; }
  finally { if (sequence === loadSequence) loading.value = false; }
}
function resetForm() {
  editing.value = null; file.value = null; clearPreview();
  if (fileInput.value) fileInput.value.value = '';
  name.value = ''; sourceStatement.value = ''; Object.assign(safeArea, defaultArea);
}
function startEdit(asset: SynthesisAsset, replace = false) {
  if (busy.value || loading.value || !manageable(asset)) return;
  resetForm(); editing.value = asset; name.value = asset.name; sourceStatement.value = asset.sourceStatement ?? '';
  Object.assign(safeArea, asset.textSafeArea); error.value = ''; message.value = '';
  if (replace) fileInput.value?.click();
  else void nextTick(() => editor.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }));
}
function chooseUpload() {
  if (busy.value) return;
  resetForm(); fileInput.value?.click();
}
async function chooseFile(event: Event) {
  if (busy.value) return;
  const input = event.target as HTMLInputElement;
  const picked = input.files?.[0]; if (!picked) return;
  error.value = ''; message.value = ''; file.value = null; clearPreview();
  if (!/\.(gif|png|jpe?g|webp)$/i.test(picked.name) || !picked.size || picked.size > 10 * 1024 * 1024) {
    error.value = '请选择 GIF、PNG、JPG 或 WebP 图片；上传通道单文件容量为 10 MiB。'; input.value = ''; return;
  }
  file.value = picked; preview.value = URL.createObjectURL(picked);
  if (!editing.value) {
    name.value = picked.name.replace(/\.[^.]+$/, '').slice(0, 100) || '未命名底图';
    await upload();
  } else void nextTick(() => editor.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }));
}
async function upload() {
  if (busy.value || disposed) return;
  error.value = ''; message.value = '';
  if ((!editing.value && !file.value) || !name.value.trim()) { error.value = '请选择图片并填写底图名称。'; return; }
  if (!Object.values(safeArea).every(Number.isInteger) || safeArea.x < 0 || safeArea.y < 0 || safeArea.width <= 0 || safeArea.height <= 0 || safeArea.x + safeArea.width > canvasWidth.value || safeArea.y + safeArea.height > canvasHeight.value) {
    error.value = '文字安全区必须为整数、宽高大于零，且不能超出底图画布。'; return;
  }
  const layout = editing.value?.layout ?? defaultLayout;
  const minimum = layout.minFontSize + 2 * layout.strokeWidth;
  if (safeArea.width < minimum || safeArea.height < minimum) { error.value = `文字安全区宽高必须至少 ${minimum} 像素，以容纳一个最小字及描边。`; return; }
  busy.value = true;
  try {
    const body = { name: name.value.trim(), sourceStatement: sourceStatement.value.trim(), textSafeArea: { ...safeArea }, layout: { ...layout } };
    let uploaded: {asset: SynthesisAsset; duplicate?: boolean; files_pending?: boolean} | undefined;
    if (file.value) {
      uploadProgress.value = 0;
      uploaded = await api.uploadSynthesisFile(file.value, { ...body, coordinateWidth: canvasWidth.value, coordinateHeight: canvasHeight.value }, percent => { uploadProgress.value = percent; }, editing.value?.id);
    }
    if (editing.value) {
      const result = uploaded ?? await api.updateSynthesisAsset(editing.value.id, body);
      if (disposed) return;
      assets.value = assets.value.map(a => a.id === result.asset.id ? result.asset : a);
      failed.value.delete(result.asset.id);
      message.value = result.files_pending ? '底图已更新，旧文件清理待重试。' : '底图已更新。';
    } else {
      const result = uploaded!;
      if (disposed) return;
      if (!assets.value.some(a => a.id === result.asset.id)) assets.value.unshift(result.asset);
      message.value = result.duplicate ? '相同图片已存在，未重复保存。' : '底图已上传入库，可在下方编辑名称和文字区域。';
    }
    ++loadSequence; loading.value = false; resetForm();
  } catch (e) { error.value = `${editing.value ? '保存' : '上传'}失败：${(e as Error).message}`; }
  finally { busy.value = false; uploadProgress.value = null; }
}
function selectAll() { if (!busy.value && !loading.value) selected.value = new Set(manageableAssets.value.map(a => a.id)); }
function clearSelection() { if (!busy.value && !loading.value) selected.value.clear(); }
function toggleSelection(id: string) {
  if (busy.value || loading.value) return;
  if (selected.value.has(id)) selected.value.delete(id); else selected.value.add(id);
}
async function removeAssets(targets: SynthesisAsset[]) {
  const rows = targets.filter(manageable);
  if (busy.value || loading.value || !rows.length) return;
  const label = rows.length === 1 ? `“${rows[0]!.name}”这张` : `选中的 ${rows.length} 张`;
  if (!(await askConfirmation(`删除${label} AI 合成底图？手机下次检查更新后移除，关键词推荐图不受影响。`))) return;
  if (disposed || busy.value || loading.value) return;
  busy.value = true; error.value = ''; message.value = '';
  let removed = 0; let cleanupPending = 0; const errors: { asset: SynthesisAsset; reason: string }[] = [];
  try {
    for (const asset of rows) {
      if (disposed) return;
      try {
        const result = await api.deleteSynthesisAsset(asset.id); if (disposed) return;
        if (result.files_pending) cleanupPending++;
        assets.value = assets.value.filter(a => a.id !== asset.id); selected.value.delete(asset.id); removed++;
        if (editing.value?.id === asset.id) resetForm();
      } catch (e) { selected.value.add(asset.id); errors.push({ asset, reason: (e as Error).message }); }
    }
    if (errors.length) { await load(); if (disposed) return; }
    const remaining = errors.filter(({ asset }) => assets.value.some(a => a.id === asset.id));
    removed += errors.length - remaining.length;
    if (removed) message.value = `已删除 ${removed} 张底图。${cleanupPending ? `${cleanupPending} 张旧文件清理未完成。` : ''}`;
    if (remaining.length) error.value = `${remaining.length} 张删除失败，已保留勾选，可重试。${remaining.map(({ asset, reason }) => `${asset.name}：${reason}`).join('；')}`;
  } finally { busy.value = false; uploadProgress.value = null; }
}
onMounted(load);
</script>

<template>
  <div class="content-library synthesis-page">
    <header class="library-intro synthesis-heading"><div><span class="eyebrow">AI SYNTHESIS LIBRARY</span><h2>AI 合成底图库</h2><p>上传后直接入库，随时调整名称和文字区域。</p></div><button class="library-button primary" :disabled="busy" @click="chooseUpload"><span aria-hidden="true">＋</span> 上传底图</button></header>
    <input ref="fileInput" data-testid="synthesis-file" class="hidden-upload" type="file" accept=".gif,.png,.jpg,.jpeg,.webp" @change="chooseFile" />
    <div class="synthesis-tip"><span>GIF / PNG / JPG / WebP · 保留原图尺寸和动画</span><span>系统底图只读，个人上传用于当前选中用户。</span></div>
    <p v-if="busy" class="library-notice" role="status">{{ uploadProgress === null ? '正在保存，请稍候…' : uploadProgress < 100 ? `正在上传 ${uploadProgress}%` : '文件已传输，正在保存…' }}</p>
    <p v-if="message" class="library-notice success" role="status">{{ message }}</p>
    <div v-if="error" class="library-notice error" role="alert">{{ error }}<button v-if="file && !editing" data-testid="retry-synthesis-upload" class="library-button small" :disabled="busy" @click="upload">重试上传</button></div>
    <div v-if="loadError" class="library-notice error" role="alert">{{ loadError }}<button data-testid="retry-synthesis" class="library-button small" :disabled="loading || busy" @click="load">重新加载</button></div>
    <section v-if="editing" ref="editor" class="library-panel synthesis-editor">
      <div class="section-heading"><div><h3>编辑底图</h3><p>可修改资料或替换 GIF，点击保存后生效。</p></div><button data-testid="cancel-synthesis-edit" class="library-button" :disabled="busy" @click="resetForm">取消编辑</button></div>
      <form data-testid="synthesis-form" @submit.prevent="upload">
        <fieldset :disabled="busy" class="upload-fields">
          <div class="synthesis-edit-grid"><div class="synthesis-edit-fields">
            <label>底图名称<input v-model="name" data-testid="synthesis-name" class="library-input" maxlength="100" /></label>
            <label>来源说明（选填）<input v-model="sourceStatement" data-testid="synthesis-source" class="library-input" maxlength="1000" placeholder="可填写素材来源" /></label>
            <div><strong>文字区域（像素）</strong><p class="synthesis-hint">蓝框为叠字范围，不会印入原图。</p><div class="safe-fields"><label v-for="field in fields" :key="field.key">{{ field.label }}<input v-model.number="safeArea[field.key]" :data-testid="`safe-${field.key}`" class="library-input" type="number" min="0"  :max="Math.max(canvasWidth, canvasHeight)" step="1" /></label></div></div>
          </div><div class="synthesis-preview-column"><div class="safe-preview" :style="{ aspectRatio: `${canvasWidth} / ${canvasHeight}` }" aria-label="GIF 与文字区域预览"><img :src="preview || scopedAssetUrl(editing.url)" alt="底图动态预览" /><div class="safe-overlay" :style="{ left: `${safeArea.x / canvasWidth * 100}%`, top: `${safeArea.y / canvasHeight * 100}%`, width: `${safeArea.width / canvasWidth * 100}%`, height: `${safeArea.height / canvasHeight * 100}%` }">文字区域</div></div><button class="library-button" type="button" @click="fileInput?.click()">替换图片</button><span v-if="file" class="synthesis-hint">已选择：{{ file.name }}</span></div></div>
          <div class="library-actions"><button data-testid="save-synthesis" class="library-button primary" type="submit" :disabled="busy || !name.trim()">保存修改</button><button class="library-button" type="button" :disabled="busy" @click="resetForm">取消</button></div>
        </fieldset>
      </form>
    </section>
    <section class="library-panel">
      <div class="section-heading"><div><h3>全部底图 <span class="library-badge">{{ loaded ? assets.length : '—' }}</span></h3><p>勾选图片后可批量删除个人底图。</p></div><button class="library-button" :disabled="loading || busy" @click="load">刷新列表</button></div>
      <div class="synthesis-selection-bar"><div class="library-actions"><button data-testid="select-all-synthesis" class="library-button small" :disabled="busy || loading || !manageableAssets.length || selected.size === manageableAssets.length" @click="selectAll">全选</button><button data-testid="clear-synthesis-selection" class="library-button small" :disabled="busy || loading || !selected.size" @click="clearSelection">全不选</button><span class="selection-count" aria-live="polite">已选 {{ selected.size }} 张</span></div><button data-testid="delete-selected-synthesis" class="library-button danger" :disabled="busy || loading || !selected.size" @click="removeAssets(assets.filter(a => selected.has(a.id)))">删除所选<span v-if="selected.size">（{{ selected.size }}）</span></button></div>
      <p v-if="loading && !loaded" role="status">正在加载底图库…</p>
      <div v-else-if="loaded && !assets.length" class="library-empty"><strong>还没有底图</strong><p>选择一张 GIF，上传后即可在这里管理。</p><button class="library-button primary" :disabled="busy" @click="chooseUpload">＋ 上传第一张底图</button></div>
      <div class="sticker-grid synthesis-grid">
        <article v-for="asset in assets" :key="asset.id" :data-testid="`synthesis-card-${asset.id}`" class="sticker-cell" :class="{ selected: selected.has(asset.id) }">
          <div class="sticker-preview"><span v-if="failed.has(asset.id)">图片加载失败，请刷新重试</span><img v-else :src="scopedAssetUrl(asset.url)" :alt="asset.name" loading="lazy" @error="failed.add(asset.id)" /><label v-if="manageable(asset)" class="synthesis-select"><input :data-testid="`select-synthesis-${asset.id}`" type="checkbox" :aria-label="`选择 ${asset.name}`" :checked="selected.has(asset.id)" :disabled="busy || loading" @change="toggleSelection(asset.id)" /></label><span class="synthesis-format">{{ asset.format.toUpperCase() }}</span></div>
          <div class="sticker-meta"><strong>{{ asset.name }}</strong><p><span class="library-badge" :class="{ personal: manageable(asset) }">{{ asset.source === 'system' ? '系统底图 · 只读' : '个人底图' }}</span></p><small>{{ asset.width }} × {{ asset.height }} · 文字区 {{ asset.textSafeArea.width }} × {{ asset.textSafeArea.height }}</small><div v-if="manageable(asset)" class="synthesis-card-actions"><button :data-testid="`edit-synthesis-${asset.id}`" class="library-button small" :disabled="busy || loading" @click="startEdit(asset)">编辑</button><button :data-testid="`replace-synthesis-${asset.id}`" class="library-button small" :disabled="busy || loading" @click="startEdit(asset, true)">替换</button><button :data-testid="`delete-synthesis-${asset.id}`" class="library-button small danger" :disabled="busy || loading" @click="removeAssets([asset])">删除</button></div></div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.synthesis-heading { justify-content:space-between; flex-wrap:wrap; }
.synthesis-page .library-button { display:inline-flex; align-items:center; justify-content:center; gap:6px; border-radius:9px; font-weight:500; }
.synthesis-page .library-button.danger { color:#b94f58; border-color:#f0d9dc; background:#fff6f6; }
.synthesis-page .library-button.danger:hover:not(:disabled) { background:#ffe9eb; border-color:#e9b8bd; }
.synthesis-tip { display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px; color:#8490a4; font-size:12px; margin-bottom:22px; line-height:1.7; }
.synthesis-page .library-panel { margin-bottom:20px; }
.synthesis-selection-bar { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; padding:14px; margin-bottom:20px; border:1px solid #e8ecf5; border-radius:12px; background:#f8f9fd; }
.selection-count { color:#8490a4; font-size:12px; margin-left:4px; }
.synthesis-grid { grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); }
.synthesis-grid .sticker-cell { transition:border-color .15s,box-shadow .15s; }
.synthesis-grid .sticker-cell.selected { border-color:#7784e4; box-shadow:0 0 0 2px #5261d81a; }
.synthesis-grid .sticker-meta>strong { display:block; overflow-wrap:anywhere; margin-bottom:8px; }
.synthesis-card-actions { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:6px; margin-top:14px; }
.synthesis-card-actions .library-button { padding:7px 4px; }
.synthesis-select { position:absolute; top:10px; left:10px; display:grid; place-items:center; padding:7px; background:#fffffff0; border-radius:8px; cursor:pointer; }
.synthesis-select input { width:18px; height:18px; margin:0; accent-color:#5261d8; cursor:pointer; }
.synthesis-format { position:absolute; bottom:10px; right:10px; padding:3px 7px; border-radius:5px; background:#ffffffdd; color:#8490a4; font-size:10px; }
.upload-fields { border:0; padding:0; display:grid; gap:20px; min-width:0; }
.synthesis-editor { scroll-margin-top:20px; }
.synthesis-edit-grid { display:flex; flex-wrap:wrap; gap:28px; align-items:start; }
.synthesis-edit-fields { flex:1; min-width:0; display:grid; gap:18px; }
.synthesis-edit-fields>label { display:grid; gap:8px; }
.synthesis-hint { color:#8490a4; font-size:12px; line-height:1.7; overflow-wrap:anywhere; }
.safe-fields { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; margin-top:12px; }
.safe-fields label { display:grid; gap:6px; font-size:12px; }
.safe-fields .library-input { min-width:0; }
.synthesis-preview-column { display:grid; gap:12px; max-width:100%; }
.safe-preview { width:240px; max-width:100%; aspect-ratio:1; position:relative; overflow:hidden; display:grid; place-items:center; background:#f5f5ef; border:1px solid #dce1ef; border-radius:10px; }
.safe-preview img { width:100%; height:100%; object-fit:contain; }
.safe-overlay { position:absolute; box-sizing:border-box; display:grid; place-items:center; border:1px dashed #5261d8; background:#5261d822; color:#34419a; font-size:12px; pointer-events:none; }
@media(max-width:760px) { .synthesis-grid { grid-template-columns:repeat(auto-fill,minmax(200px,1fr)); } .synthesis-edit-fields { flex-basis:100%; } .synthesis-selection-bar { padding:10px; } }
</style>
