<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  api,
  type IncomingStickerAsset,
  type RelationshipProfileInput,
  type RelationshipRow,
  type RelationshipType,
  type StickerAssetCandidate,
  type StickerCandidateSource,
  type ZeroTokenCandidateRow,
} from '../api';

const relationships = ref<RelationshipRow[]>([]);
const selected = ref<RelationshipRow | null>(null);
const form = ref<RelationshipProfileInput>({
  relationship_type: 'unknown',
  alias: null,
  intimacy_level: 50,
  humor_level: 50,
  notes: null,
});
const contextText = ref('');
const candidates = ref<ZeroTokenCandidateRow[]>([]);
const incomingStickers = ref<IncomingStickerAsset[]>([]);
const selectedIncomingSha256 = ref<string | null>(null);
const stickerCandidates = ref<StickerAssetCandidate[]>([]);
const loading = ref(false);
const saving = ref(false);
const stickerLoading = ref(false);
const error = ref('');
const saved = ref('');

const relationshipTypes: Array<{ value: RelationshipType; label: string }> = [
  { value: 'unknown', label: '未分类' },
  { value: 'friend', label: '朋友' },
  { value: 'family', label: '家人' },
  { value: 'partner', label: '伴侣' },
  { value: 'colleague', label: '同事' },
  { value: 'customer', label: '客户' },
  { value: 'group', label: '群聊' },
  { value: 'other', label: '其他' },
];
const platformNames = { wechat: '微信', qq: 'QQ', douyin: '抖音' } as const;
const sourceNames = {
  context_match: '相同上下文',
  conversation_frequency: '当前关系高频',
  relationship_type_frequency: '同关系类型',
  global_frequency: '用户通用',
} as const;
const stickerSourceNames: Record<StickerCandidateSource, string> = {
  sticker_counterattack: '相同表情反击',
  sticker_conversation_frequency: '当前关系高频',
  sticker_relationship_type_frequency: '同关系类型',
  sticker_global_frequency: '用户通用',
};
const selectedTitle = computed(() => selected.value?.alias
  || selected.value?.display_name
  || selected.value?.external_key
  || '关系档案');

function formatTime(value: string | null): string {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function selectRelationship(row: RelationshipRow): void {
  selected.value = row;
  form.value = {
    relationship_type: row.relationship_type,
    alias: row.alias,
    intimacy_level: row.intimacy_level,
    humor_level: row.humor_level,
    notes: row.notes,
  };
  contextText.value = '';
  candidates.value = [];
  incomingStickers.value = [];
  selectedIncomingSha256.value = null;
  stickerCandidates.value = [];
  error.value = '';
  saved.value = '';
  void loadStickerPreview(row.conversation_id);
}

async function loadStickerPreview(conversationId: number): Promise<void> {
  stickerLoading.value = true;
  try {
    const [incoming, preview] = await Promise.all([
      api.relationshipIncomingStickerAssets(conversationId),
      api.relationshipStickerCandidates(conversationId, null),
    ]);
    if (selected.value?.conversation_id !== conversationId) return;
    incomingStickers.value = incoming.assets;
    stickerCandidates.value = preview.candidates;
  } catch (reason) {
    if (selected.value?.conversation_id === conversationId) {
      error.value = `表情预览加载失败：${(reason as Error).message}`;
    }
  } finally {
    if (selected.value?.conversation_id === conversationId) stickerLoading.value = false;
  }
}

async function load(preferredId?: number): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    const result = await api.relationships();
    relationships.value = result.relationships;
    const next = relationships.value.find((row) => row.conversation_id === preferredId)
      ?? relationships.value[0];
    if (next) selectRelationship(next);
  } catch (reason) {
    error.value = `加载关系档案失败：${(reason as Error).message}`;
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  if (!selected.value) return;
  saving.value = true;
  error.value = '';
  saved.value = '';
  try {
    await api.updateRelationship(selected.value.conversation_id, form.value);
    const id = selected.value.conversation_id;
    await load(id);
    saved.value = '关系档案已保存';
  } catch (reason) {
    error.value = `保存失败：${(reason as Error).message}`;
  } finally {
    saving.value = false;
  }
}

async function preview(): Promise<void> {
  if (!selected.value) return;
  error.value = '';
  try {
    const result = await api.relationshipCandidates(
      selected.value.conversation_id,
      contextText.value,
    );
    candidates.value = result.candidates;
  } catch (reason) {
    error.value = `候选预览失败：${(reason as Error).message}`;
  }
}

async function previewStickers(incomingSha256: string | null): Promise<void> {
  if (!selected.value) return;
  const conversationId = selected.value.conversation_id;
  selectedIncomingSha256.value = incomingSha256;
  stickerLoading.value = true;
  error.value = '';
  try {
    const result = await api.relationshipStickerCandidates(conversationId, incomingSha256);
    if (selected.value?.conversation_id !== conversationId
      || selectedIncomingSha256.value !== incomingSha256) return;
    stickerCandidates.value = result.candidates;
  } catch (reason) {
    error.value = `表情候选预览失败：${(reason as Error).message}`;
  } finally {
    if (selected.value?.conversation_id === conversationId
      && selectedIncomingSha256.value === incomingSha256) {
      stickerLoading.value = false;
    }
  }
}

onMounted(() => load());
</script>

<template>
  <p v-if="error" class="notice error">{{ error }}</p>
  <p v-if="saved" class="notice success">{{ saved }}</p>

  <div class="relationship-layout">
    <section class="card relationship-list">
      <h3>聊天关系</h3>
      <p v-if="loading" class="empty">加载中…</p>
      <p v-else-if="relationships.length === 0" class="empty">暂无可建档会话</p>
      <button
        v-for="row in relationships"
        :key="row.conversation_id"
        class="relationship-item"
        :class="{ selected: selected?.conversation_id === row.conversation_id }"
        @click="selectRelationship(row)"
      >
        <strong>{{ row.alias || row.display_name || row.external_key }}</strong>
        <span>{{ platformNames[row.platform] }} · {{ row.message_count }} 条消息</span>
        <span>{{ relationshipTypes.find((item) => item.value === row.relationship_type)?.label }}</span>
      </button>
    </section>

    <div class="detail-column">
      <section class="card profile-card">
        <h3>{{ selectedTitle }}</h3>
        <p v-if="!selected" class="empty">请选择一个聊天关系</p>
        <form v-else class="profile-form" @submit.prevent="save">
          <label>
            <span>关系类型</span>
            <select v-model="form.relationship_type">
              <option v-for="item in relationshipTypes" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </label>
          <label>
            <span>专属称呼</span>
            <input v-model="form.alias" maxlength="100" placeholder="可选" />
          </label>
          <label>
            <span>亲密度：{{ form.intimacy_level }}</span>
            <input v-model.number="form.intimacy_level" type="range" min="0" max="100" />
          </label>
          <label>
            <span>玩笑尺度：{{ form.humor_level }}</span>
            <input v-model.number="form.humor_level" type="range" min="0" max="100" />
          </label>
          <label class="wide">
            <span>关系备注</span>
            <textarea v-model="form.notes" maxlength="2000" rows="4" placeholder="仅人工维护，不会调用 AI" />
          </label>
          <div class="profile-meta wide">
            最近聊天：{{ formatTime(selected.last_message_at) }} · 会话键：{{ selected.external_key }}
          </div>
          <button class="primary" type="submit" :disabled="saving">
            {{ saving ? '保存中…' : '保存档案' }}
          </button>
        </form>
      </section>

      <section class="card candidate-card">
        <h3>零 Token 候选预览</h3>
        <p class="hint">只检索你真实发送过的文字，不调用 AI。</p>
        <div class="preview-input">
          <input
            v-model="contextText"
            maxlength="20000"
            placeholder="输入对方刚说的话；留空则查看高频回复"
            @keyup.enter="preview"
          />
          <button class="primary" type="button" :disabled="!selected" @click="preview">生成候选</button>
        </div>
        <p v-if="candidates.length === 0" class="empty">暂无候选</p>
        <div v-else class="candidate-list">
          <article v-for="candidate in candidates" :key="`${candidate.source}:${candidate.text}`">
            <p>{{ candidate.text }}</p>
            <span>{{ sourceNames[candidate.source] }} · 使用 {{ candidate.use_count }} 次 · {{ formatTime(candidate.last_used_at) }}</span>
          </article>
        </div>
      </section>

      <section class="card candidate-card">
        <h3>零 Token 表情反击预览</h3>
        <p class="hint">只使用采集到的真实收发表情和精确哈希，不调用 AI。</p>
        <div class="incoming-stickers">
          <button
            type="button"
            class="sticker-selector frequency-selector"
            :class="{ selected: selectedIncomingSha256 === null }"
            :disabled="!selected || stickerLoading"
            @click="previewStickers(null)"
          >
            关系高频
          </button>
          <button
            v-for="asset in incomingStickers"
            :key="asset.sha256"
            type="button"
            class="sticker-selector"
            :class="{ selected: selectedIncomingSha256 === asset.sha256 }"
            :title="`最近收到：${formatTime(asset.last_seen_at)}`"
            :disabled="stickerLoading"
            @click="previewStickers(asset.sha256)"
          >
            <img :src="asset.url" alt="收到的表情" />
          </button>
        </div>
        <p v-if="stickerLoading" class="empty">加载表情候选中…</p>
        <p v-else-if="stickerCandidates.length === 0" class="empty">暂无表情候选</p>
        <div v-else class="sticker-candidate-list">
          <article v-for="candidate in stickerCandidates" :key="candidate.sha256">
            <img :src="candidate.url" alt="候选表情" />
            <span>{{ stickerSourceNames[candidate.source] }}</span>
            <small>使用 {{ candidate.use_count }} 次</small>
            <small>{{ formatTime(candidate.last_used_at) }}</small>
          </article>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.relationship-layout { display: grid; grid-template-columns: minmax(220px, 300px) minmax(0, 1fr); gap: 20px; }
.relationship-list { margin: 0; }
.relationship-item { width: 100%; display: grid; gap: 4px; padding: 12px; border: 0; border-bottom: 1px solid #f1f2f6; background: transparent; text-align: left; cursor: pointer; }
.relationship-item:hover, .relationship-item.selected { background: #f1f3ff; }
.relationship-item.selected { box-shadow: inset 3px 0 #3742fa; }
.relationship-item strong { color: #2f3542; }
.relationship-item span { color: #747d8c; font-size: 12px; }
.detail-column { min-width: 0; }
.profile-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.profile-form label { display: grid; gap: 6px; color: #57606f; font-size: 13px; }
.profile-form .wide { grid-column: 1 / -1; }
input, select, textarea { width: 100%; padding: 9px 10px; border: 1px solid #dfe4ea; border-radius: 6px; background: #fff; color: #2f3542; font: inherit; }
textarea { resize: vertical; }
.profile-meta, .hint { color: #747d8c; font-size: 12px; }
.primary { width: fit-content; padding: 8px 16px; border: 0; border-radius: 6px; background: #3742fa; color: #fff; cursor: pointer; }
.primary:disabled { opacity: .55; cursor: not-allowed; }
.preview-input { display: flex; gap: 10px; margin-top: 12px; }
.preview-input input { flex: 1; }
.candidate-list { display: grid; gap: 10px; margin-top: 16px; }
.candidate-list article { padding: 12px 14px; border-radius: 8px; background: #f7f8ff; }
.candidate-list p { white-space: pre-wrap; word-break: break-word; }
.candidate-list span { display: block; margin-top: 6px; color: #747d8c; font-size: 12px; }
.incoming-stickers { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; }
.sticker-selector { display: grid; place-items: center; width: 72px; height: 72px; padding: 5px; border: 1px solid #dfe4ea; border-radius: 8px; background: #fff; cursor: pointer; }
.sticker-selector:hover, .sticker-selector.selected { border-color: #3742fa; background: #f1f3ff; box-shadow: 0 0 0 2px rgb(55 66 250 / 12%); }
.sticker-selector:disabled { opacity: .55; cursor: not-allowed; }
.sticker-selector img { max-width: 100%; max-height: 100%; object-fit: contain; }
.frequency-selector { color: #3742fa; font-size: 12px; font-weight: 600; }
.sticker-candidate-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 12px; margin-top: 16px; }
.sticker-candidate-list article { display: grid; justify-items: center; gap: 5px; padding: 12px; border-radius: 8px; background: #f7f8ff; text-align: center; }
.sticker-candidate-list img { width: 88px; height: 88px; object-fit: contain; }
.sticker-candidate-list span { color: #2f3542; font-size: 12px; font-weight: 600; }
.sticker-candidate-list small { color: #747d8c; font-size: 11px; }
.notice { padding: 10px 14px; margin-bottom: 16px; border-radius: 6px; }
.error { background: #fff0f0; color: #c0392b; }
.success { background: #effaf3; color: #218c4f; }
@media (max-width: 820px) {
  .relationship-layout { grid-template-columns: 1fr; }
  .profile-form { grid-template-columns: 1fr; }
  .profile-form .wide { grid-column: auto; }
  .preview-input { flex-direction: column; }
}
</style>
