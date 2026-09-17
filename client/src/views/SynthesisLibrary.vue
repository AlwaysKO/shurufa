<script setup lang="ts">
import { useConfirmation } from '../confirmation';
import { onMounted, onBeforeUnmount, reactive, ref } from 'vue';
import { api, scopedAssetUrl, type SynthesisAsset } from '../api';
import './content-library.css';

const askConfirmation = useConfirmation();

const assets = ref<SynthesisAsset[]>([]);
const loading = ref(false); const loaded = ref(false); const busy = ref(false);
const loadError = ref(''); const error = ref(''); const message = ref('');
const name = ref(''); const sourceStatement = ref('');
const noTextConfirmed = ref(false); const rightsConfirmed = ref(false);
const file = ref<File | null>(null); const fileInput = ref<HTMLInputElement | null>(null);
const preview = ref(''); const failed = ref(new Set<string>());
const safeArea = reactive({ x: 6, y: 190, width: 228, height: 44 });
const fields = [{ key: 'x', label: '左侧 X' }, { key: 'y', label: '顶部 Y' }, { key: 'width', label: '宽度' }, { key: 'height', label: '高度' }] as const;
function clearPreview() { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ''; }
let disposed = false;
onBeforeUnmount(() => { disposed = true; clearPreview(); });
async function load() {
  loading.value = true; loadError.value = '';
  try { assets.value = (await api.synthesisLibrary()).assets; loaded.value = true; failed.value.clear(); }
  catch (e) { loadError.value = `底图库加载失败：${(e as Error).message}`; }
  finally { loading.value = false; }
}
function chooseFile(event: Event) {
  if (busy.value) return;
  const input = event.target as HTMLInputElement;
  const selected = input.files?.[0]; if (!selected) return;
  error.value = ''; message.value = ''; file.value = null; clearPreview();
  noTextConfirmed.value = false; rightsConfirmed.value = false;
  if (!/\.gif$/i.test(selected.name) || !selected.size || selected.size > 250 * 1024) {
    error.value = '请选择非空动态 GIF，240 × 240，单张不超过 250 KB。'; input.value = ''; return;
  }
  file.value = selected; preview.value = URL.createObjectURL(selected);
  if (!name.value.trim()) name.value = selected.name.replace(/\.gif$/i, '').slice(0, 100);
}
async function upload() {
  if (busy.value) return;
  error.value = ''; message.value = '';
  if (!file.value || !name.value.trim() || !sourceStatement.value.trim() || !noTextConfirmed.value || !rightsConfirmed.value) {
    error.value = '请选择 GIF、填写名称和来源，并确认无字及合法使用权限。'; return;
  }
  if (!Object.values(safeArea).every(Number.isInteger) || safeArea.x < 0 || safeArea.y < 0 || safeArea.width <= 0 || safeArea.height <= 0 || safeArea.x + safeArea.width > 240 || safeArea.y + safeArea.height > 240) {
    error.value = '文字安全区必须为整数、宽高大于零，且不能超出 240 × 240 画布。'; return;
  }
  if (safeArea.width < 14 || safeArea.height < 14) {
    error.value = '文字安全区宽高必须至少 14 像素，以容纳一个最小字及描边。'; return;
  }
  busy.value = true;
  try {
    const bytes = new Uint8Array(await file.value.arrayBuffer()); let binary = '';
    if (disposed) return; // 切换当前用户后不将旧表单上传到新用户。
    for (const byte of bytes) binary += String.fromCharCode(byte);
    const result = await api.uploadSynthesisAsset({ file_base64: btoa(binary), filename: file.value.name,
      name: name.value.trim(), sourceStatement: sourceStatement.value.trim(), noTextConfirmed: true, rightsConfirmed: true,
      textSafeArea: { ...safeArea }, layout: { minFontSize: 12, maxFontSize: 24, textColor: '#222222', strokeColor: '#ffffff', strokeWidth: 1, alignment: 'center', maxLines: 2 } });
    message.value = result.duplicate ? '相同 GIF 已存在，未重复保存。' : '已加入 AI 合成底图库；手机下次打开键盘检查更新后可补充，不进入关键词推荐图。';
    file.value = null; clearPreview(); if (fileInput.value) fileInput.value.value = '';
    name.value = ''; sourceStatement.value = ''; noTextConfirmed.value = false; rightsConfirmed.value = false;
    await load();
  } catch (e) { error.value = `上传失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
async function remove(asset: SynthesisAsset) {
  if (busy.value || !asset.deletable || asset.source !== 'personal' || !(await askConfirmation(`删除“${asset.name}”这张 AI 合成底图？不会删除关键词推荐图。手机下次检查更新后移除。`))) return;
  if (busy.value || loading.value) return;
  busy.value = true; error.value = ''; message.value = '';
  try { await api.deleteSynthesisAsset(asset.id); message.value = '底图已删除，关键词推荐图库不受影响。'; await load(); }
  catch (e) { error.value = `删除失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
onMounted(load);
</script>

<template>
  <div class="content-library synthesis-page">
    <header class="library-intro"><div><span class="eyebrow">AI SYNTHESIS LIBRARY</span><h2>无字底图，配上自己的话</h2><p>此库仅用于 AI 文字合成，与关键词推荐图库分开。系统素材只读，个人上传仅对当前选中用户生效。</p></div></header>
    <section class="library-panel">
      <div class="section-heading"><div><h3>上传无字动态 GIF</h3><p>240 × 240，最多 250 KB，必须有真实动画。按文件 SHA 去重；名称不同也不会重复入库。</p></div></div>
      <form data-testid="synthesis-form" @submit.prevent="upload">
        <fieldset :disabled="busy" class="upload-fields">
          <label>GIF 文件<input ref="fileInput" data-testid="synthesis-file" type="file" accept=".gif,image/gif" @change="chooseFile" /></label>
          <label>底图名称<input v-model="name" data-testid="synthesis-name" class="library-input" maxlength="100" placeholder="例如：熊猫滑稽扭舞" /></label>
          <label>来源与许可说明<input v-model="sourceStatement" data-testid="synthesis-source" class="library-input" maxlength="1000" placeholder="原创说明，或授权方、许可范围及依据" /></label>
          <div class="safe-area-editor">
            <div><strong>文字安全区（像素）</strong><p class="upload-hint">默认留底部两行。调整蓝框避开角色；框只是叠字范围，不会印入 GIF。</p>
              <div class="safe-fields"><label v-for="field in fields" :key="field.key">{{ field.label }}<input v-model.number="safeArea[field.key]" :data-testid="`safe-${field.key}`" class="library-input" type="number" min="0" max="240" step="1" /></label></div>
            </div>
            <div class="safe-preview" aria-label="GIF 与文字安全区预览"><img v-if="preview" :src="preview" alt="待上传 GIF 动态预览" /><span v-else>选择 GIF 后预览</span><div class="safe-overlay" :style="{ left: `${safeArea.x / 2.4}%`, top: `${safeArea.y / 2.4}%`, width: `${safeArea.width / 2.4}%`, height: `${safeArea.height / 2.4}%` }">文字区域</div></div>
          </div>
          <label class="confirmation"><input v-model="noTextConfirmed" data-testid="synthesis-no-text" type="checkbox" />我已检查整段动画，没有预印文字，并留有可叠字空间。</label>
          <label class="confirmation"><input v-model="rightsConfirmed" data-testid="synthesis-rights" type="checkbox" />我拥有此素材用于产品合成与分发的合法权限；此声明不代表平台独立核验。</label>
          <button class="library-button primary" type="submit" :disabled="busy || !file || !name.trim() || !sourceStatement.trim() || !noTextConfirmed || !rightsConfirmed">{{ busy ? '处理中…' : '加入 AI 合成底图库' }}</button>
        </fieldset>
      </form>
    </section>
    <p v-if="message" class="library-notice success" role="status">{{ message }}</p>
    <p v-if="error" class="library-notice error" role="alert">{{ error }}</p>
    <div v-if="loadError" class="library-notice error" role="alert">{{ loadError }}<button data-testid="retry-synthesis" class="text-button" :disabled="loading || busy" @click="load">重试</button></div>
    <section class="library-panel">
      <div class="section-heading"><h3>AI 合成底图 · {{ loaded ? assets.length : '—' }} 张</h3><button class="library-button" :disabled="loading || busy" @click="load">刷新</button></div>
      <p v-if="loading && !loaded" role="status">正在加载底图库…</p>
      <p v-else-if="loaded && !assets.length" class="library-empty">暂无底图，请上传合格的无字动态 GIF。</p>
      <div class="sticker-grid">
        <article v-for="asset in assets" :key="asset.id" class="sticker-cell">
          <div class="sticker-preview"><span v-if="failed.has(asset.id)">图片加载失败，请刷新重试</span><img v-else :src="scopedAssetUrl(asset.url)" :alt="asset.name" loading="lazy" @error="failed.add(asset.id)" /></div>
          <div class="sticker-meta"><strong>{{ asset.name }}</strong><p><span class="library-badge">{{ asset.source === 'system' ? '系统底图 · 只读' : '个人无字底图' }}</span></p><small>{{ asset.width }} × {{ asset.height }} · GIF</small><p class="upload-hint">文字区：{{ asset.textSafeArea.x }}, {{ asset.textSafeArea.y }} / {{ asset.textSafeArea.width }} × {{ asset.textSafeArea.height }}</p><button v-if="asset.deletable && asset.source === 'personal'" :data-testid="`delete-synthesis-${asset.id}`" class="text-button danger" :disabled="busy || loading" @click="remove(asset)">删除底图</button></div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.upload-fields { border:0; padding:0; display:grid; gap:18px; min-width:0; }
.upload-fields > label:not(.confirmation) { display:grid; gap:8px; }
.confirmation { display:flex; align-items:flex-start; gap:9px; line-height:1.7; }
.confirmation input { margin-top:5px; }
.safe-area-editor { display:flex; flex-wrap:wrap; gap:24px; align-items:center; }
.safe-fields { display:grid; grid-template-columns:repeat(4,minmax(50px,100px)); gap:10px; margin-top:12px; }
.safe-fields label { display:grid; gap:6px; }
.safe-preview { width:240px; height:240px; position:relative; overflow:hidden; display:grid; place-items:center; background:#f5f5ef; border:1px solid #dce1ef; flex-shrink:0; }
.safe-preview img { width:100%; height:100%; object-fit:contain; }
.safe-overlay { position:absolute; box-sizing:border-box; display:grid; place-items:center; border:1px dashed #5261d8; background:#5261d822; color:#34419a; font-size:12px; pointer-events:none; }
.synthesis-page .library-panel { margin-bottom:20px; }
.upload-fields > button { justify-self:start; }
</style>
