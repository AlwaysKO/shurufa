<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, type StickerRow } from '../api';

const stickers = ref<StickerRow[]>([]);
const total = ref(0);
const q = ref('');
const loading = ref(false);

// 上传表单
const uploadMsg = ref('');
const uploadErr = ref('');
const uploading = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
const keywords = ref('');

async function load() {
  loading.value = true;
  try {
    const data = await api.stickers(q.value);
    stickers.value = data.stickers;
    total.value = data.total;
  } catch (e) {
    uploadErr.value = `加载失败：${(e as Error).message}`;
  } finally {
    loading.value = false;
  }
}

/** 读取图片尺寸（用于展示时保持比例） */
function readImageSize(file: File): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve({ width: img.naturalWidth, height: img.naturalHeight });
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('无法读取图片'));
    };
    img.src = url;
  });
}

async function doUpload() {
  const file = fileInput.value?.files?.[0];
  if (!file) {
    uploadErr.value = '请先选择图片文件';
    return;
  }
  if (!keywords.value.trim()) {
    uploadErr.value = '请填写关键词（逗号分隔，如：无语,离谱）';
    return;
  }
  uploading.value = true;
  uploadMsg.value = '';
  uploadErr.value = '';
  try {
    const buf = await file.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    const { width, height } = await readImageSize(file);
    await api.uploadSticker({
      file_base64: btoa(bin),
      filename: file.name,
      keywords: keywords.value.trim(),
      width,
      height,
    });
    uploadMsg.value = `已上传（${width}×${height}）`;
    keywords.value = '';
    if (fileInput.value) fileInput.value.value = '';
    await load();
  } catch (e) {
    uploadErr.value = `上传失败：${(e as Error).message}`;
  } finally {
    uploading.value = false;
  }
}

const editingId = ref<number | null>(null);
const editingKeywords = ref('');

function startEdit(s: StickerRow) {
  editingId.value = s.id;
  editingKeywords.value = s.keywords;
}

async function saveEdit(s: StickerRow) {
  try {
    await api.updateStickerKeywords(s.id, editingKeywords.value.trim());
    s.keywords = editingKeywords.value.trim();
  } catch (e) {
    uploadErr.value = `保存失败：${(e as Error).message}`;
  }
  editingId.value = null;
}

async function doDelete(s: StickerRow) {
  if (!confirm(`删除表情包 #${s.id}？图片文件将一并删除。`)) return;
  try {
    await api.deleteSticker(s.id);
    stickers.value = stickers.value.filter((x) => x.id !== s.id);
    total.value -= 1;
  } catch (e) {
    uploadErr.value = `删除失败：${(e as Error).message}`;
  }
}

const filtered = computed(() => stickers.value);
onMounted(load);
</script>

<template>
  <div class="card">
    <h3>上传表情包</h3>
    <p class="desc">支持 gif / png / jpg / webp（建议 &lt; 5MB）。上传后即可在输入法的「斗图」面板中按关键词搜索使用。</p>
    <div class="form-row">
      <input ref="fileInput" type="file" accept=".gif,.png,.jpg,.jpeg,.webp" />
      <input v-model="keywords" class="input" placeholder="关键词，逗号分隔（如：无语,离谱,问号）" style="flex: 2" />
      <button class="primary" :disabled="uploading" @click="doUpload">{{ uploading ? '上传中…' : '上传' }}</button>
    </div>
    <p v-if="uploadMsg" class="msg ok">{{ uploadMsg }}</p>
    <p v-if="uploadErr" class="msg err">{{ uploadErr }}</p>
  </div>

  <div class="card">
    <h3>表情包库 <span class="count">{{ total }} 个</span></h3>
    <div class="form-row">
      <input v-model="q" class="input" placeholder="搜索关键词…" @keyup.enter="load" />
      <button class="primary" :disabled="loading" @click="load">搜索</button>
    </div>

    <p v-if="loading" class="msg">加载中…</p>
    <p v-else-if="stickers.length === 0" class="msg">暂无表情包，先上传一个吧</p>

    <div v-else class="sticker-grid">
      <div v-for="s in filtered" :key="s.id" class="sticker-cell">
        <img :src="s.url" :alt="s.keywords" loading="lazy" />
        <div class="sticker-meta">
          <template v-if="editingId === s.id">
            <input v-model="editingKeywords" class="input" @keyup.enter="saveEdit(s)" />
          </template>
          <template v-else>
            <span class="kw" :title="s.keywords">{{ s.keywords }}</span>
          </template>
          <div class="actions">
            <button class="ghost" @click="editingId === s.id ? saveEdit(s) : startEdit(s)">
              {{ editingId === s.id ? '保存' : '改词' }}
            </button>
            <button class="ghost danger" @click="doDelete(s)">删除</button>
          </div>
        </div>
        <div class="sticker-stats">使用 {{ s.useCount }} 次 · {{ s.format.toUpperCase() }} · {{ s.width }}×{{ s.height }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.count {
  font-weight: normal;
  color: var(--muted, #888);
  font-size: 0.85em;
}
.sticker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.sticker-cell {
  border: 1px solid var(--border, #ddd);
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.sticker-cell img {
  width: 100%;
  height: 140px;
  object-fit: contain;
  background: repeating-conic-gradient(#f3f3f3 0% 25%, #fff 0% 50%) 50% / 16px 16px;
}
.sticker-meta {
  padding: 6px 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 4px;
}
.kw {
  font-size: 0.85em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.ghost {
  background: none;
  border: 1px solid var(--border, #ddd);
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 0.8em;
  cursor: pointer;
}
.ghost.danger {
  color: #c0392b;
}
.sticker-stats {
  padding: 4px 8px 6px;
  font-size: 0.75em;
  color: var(--muted, #888);
  border-top: 1px dashed var(--border, #eee);
}
</style>
