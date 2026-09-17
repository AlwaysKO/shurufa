<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  api,
  type ChatCaptureOverview,
  type ChatConversationRow,
  type ChatMessageRow,
  scopedAssetUrl,
} from '../api';

const overview = ref<ChatCaptureOverview>({
  conversation_count: 0,
  message_count: 0,
  media_count: 0,
});
const conversations = ref<ChatConversationRow[]>([]);
const selected = ref<ChatConversationRow | null>(null);
const messages = ref<ChatMessageRow[]>([]);
const messageType = ref('all');
const page = ref(1);
const pageSize = 24;
const total = ref(0);
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const messageError = ref('');
let latestRequest = 0;
let disposed = false;
const loading = ref(false);
const error = ref('');
const deleting = ref(false);
const deletingAssetId = ref<number | null>(null);
const previewImage = ref<{ src: string; alt: string } | null>(null);

const platformNames = { wechat: '微信', qq: 'QQ', douyin: '抖音' } as const;
const directionNames = { incoming: '收到', outgoing: '发送', system: '系统' } as const;
const messageTypes = computed(() => [
  ...new Set(messages.value.map((message) => message.message_type)),
]);
const visibleMessages = computed(() => messageType.value === 'all'
  ? messages.value
  : messages.value.filter((message) => message.message_type === messageType.value));

function formatTime(value: string | null): string {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function formatImageLabelTime(value: string): string {
  const date = new Date(value);
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function messageDisplayName(message: ChatMessageRow): string {
  if (message.metadata.capture_source === 'wechat_empty_tree_screenshot' && selected.value) {
    const chatName = selected.value.display_name || selected.value.external_key;
    return `${chatName} ${formatImageLabelTime(message.captured_at)}`;
  }
  return message.sender_name || message.sender_key;
}

function openImagePreview(url: string, alt: string) {
  previewImage.value = { src: scopedAssetUrl(url), alt };
}

function closeImagePreview() {
  previewImage.value = null;
}

async function loadMessages() {
  const conversation = selected.value;
  if (!conversation) return;
  const request = ++latestRequest;
  messageError.value = '';
  messages.value = [];
  loading.value = true;
  try {
    // 服务端先按 captured_at DESC、id DESC 排序，再分页；不对当前页单独排序。
    const result = await api.chatMessages(conversation.id, page.value, pageSize);
    if (disposed || request !== latestRequest) return;
    total.value = result.total;
    if (page.value > totalPages.value) {
      page.value = totalPages.value;
      await loadMessages();
      return;
    }
    messages.value = result.messages;
    if (!messageTypes.value.includes(messageType.value)) messageType.value = 'all';
  } catch (reason) {
    if (!disposed && request === latestRequest) messageError.value = `加载消息失败：${(reason as Error).message}`;
  } finally {
    if (!disposed && request === latestRequest) loading.value = false;
  }
}

async function selectConversation(conversation: ChatConversationRow) {
  if (deleting.value || deletingAssetId.value !== null) return;
  selected.value = conversation;
  error.value = '';
  page.value = 1;
  total.value = 0;
  messageType.value = 'all';
  await loadMessages();
}

async function changePage(next: number) {
  if (loading.value || deleting.value || deletingAssetId.value !== null) return;
  page.value = Math.min(totalPages.value, Math.max(1, next));
  messageType.value = 'all';
  await loadMessages();
}

async function load() {
  loading.value = true;
  error.value = '';
  messageError.value = '';
  try {
    const [overviewResult, conversationResult] = await Promise.all([
      api.chatCaptureOverview(),
      api.chatConversations(),
    ]);
    if (disposed) return;
    overview.value = overviewResult;
    conversations.value = conversationResult.conversations;
    selected.value = conversations.value[0] ?? null;
    page.value = 1;
    total.value = 0;
    messageType.value = 'all';
    if (selected.value) await loadMessages();
    else loading.value = false;
  } catch (reason) {
    if (disposed) return;
    error.value = `加载采集数据失败：${(reason as Error).message}`;
    loading.value = false;
  }
}

async function deleteSelectedConversation() {
  const conversation = selected.value;
  if (!conversation || loading.value || deleting.value || deletingAssetId.value !== null) return;
  const name = conversation.display_name || conversation.external_key;
  if (!window.confirm(`确定删除“${name}”及其全部聊天记录吗？此操作不可恢复。`)) return;
  deleting.value = true;
  error.value = '';
  try {
    await api.deleteChatConversation(conversation.id);
    selected.value = null;
    messages.value = [];
    await load();
  } catch (reason) {
    error.value = `删除会话失败：${(reason as Error).message}`;
  } finally {
    deleting.value = false;
  }
}

async function deleteImage(message: ChatMessageRow, assetId: number) {
  if (loading.value || deleting.value || deletingAssetId.value !== null) return;
  if (!window.confirm('确定删除这张聊天截图吗？此操作不可恢复。')) return;
  deletingAssetId.value = assetId;
  error.value = '';
  try {
    await api.deleteChatImage(message.id, assetId);
    const conversation = selected.value;
    if (conversation) await loadMessages();
    const [overviewResult, conversationResult] = await Promise.all([
      api.chatCaptureOverview(),
      api.chatConversations(),
    ]);
    if (disposed) return;
    overview.value = overviewResult;
    conversations.value = conversationResult.conversations;
    selected.value = conversation ? conversations.value.find((item) => item.id === conversation.id) ?? null : null;
  } catch (reason) {
    error.value = `删除图片失败：${(reason as Error).message}`;
  } finally {
    deletingAssetId.value = null;
  }
}

onMounted(load);
onBeforeUnmount(() => { disposed = true; latestRequest += 1; });
</script>

<template>
  <div class="stat-grid capture-stats">
    <div class="stat"><div class="num">{{ overview.conversation_count }}</div><div class="label">会话</div></div>
    <div class="stat"><div class="num">{{ overview.message_count }}</div><div class="label">消息</div></div>
    <div class="stat"><div class="num">{{ overview.media_count }}</div><div class="label">媒体资源</div></div>
  </div>

  <p v-if="error" class="error">{{ error }}</p>

  <div class="capture-layout">
    <section class="card conversation-panel">
      <h3>会话列表</h3>
      <div class="conversation-list">
        <p v-if="conversations.length === 0" class="empty">暂无采集会话</p>
        <button
          v-for="conversation in conversations"
          :key="conversation.id"
          class="conversation"
          :disabled="deleting || deletingAssetId !== null"
          :class="{ selected: selected?.id === conversation.id }"
          @click="selectConversation(conversation)"
        >
          <span class="conversation-title">{{ conversation.display_name || conversation.external_key }}</span>
          <span class="conversation-meta">
            {{ platformNames[conversation.platform] }} · {{ conversation.message_count }} 条
          </span>
          <span class="conversation-time">{{ formatTime(conversation.last_message_at) }}</span>
        </button>
      </div>
    </section>

    <section class="card timeline-panel">
      <div class="timeline-header">
        <div class="timeline-title">
          <h3>{{ selected?.display_name || selected?.external_key || '消息时间线' }}</h3>
          <span class="timeline-summary">共 {{ total }} 条 · 每页 {{ pageSize }} 条 · 采集时间倒序，最新在前</span>
        </div>
        <div class="timeline-actions">
          <select v-model="messageType" aria-label="本页消息类型筛选" :disabled="loading">
            <option value="all">本页全部类型</option>
            <option v-for="type in messageTypes" :key="type" :value="type">{{ type }}</option>
          </select>
          <button
            class="delete-button"
            type="button"
            :disabled="!selected || loading || deleting || deletingAssetId !== null"
            @click="deleteSelectedConversation"
          >{{ deleting ? '删除中…' : '删除会话' }}</button>
        </div>
      </div>

      <p v-if="messageError" class="error" role="alert">
        {{ messageError }}
        <button type="button" data-testid="chat-retry" :disabled="loading" @click="loadMessages">重试</button>
      </p>
      <p v-if="loading" class="empty">加载中…</p>
      <p v-else-if="!messageError && visibleMessages.length === 0" class="empty">暂无消息</p>
      <div v-else-if="!messageError" class="timeline" data-testid="chat-gallery">
        <article
          v-for="message in visibleMessages"
          :key="message.id"
          class="message"
          :class="[message.direction, { 'has-media': message.assets.length > 0 }]"
        >
          <div class="message-head">
            <span :data-testid="`chat-image-label-${message.id}`">{{ messageDisplayName(message) }}</span>
            <span class="badge">{{ directionNames[message.direction] }}</span>
            <span class="badge">{{ message.message_type }}</span>
            <time :datetime="message.captured_at" title="采集时间">{{ formatTime(message.captured_at) }}</time>
          </div>
          <p v-if="message.text" class="message-text">{{ message.text }}</p>
          <div v-if="message.assets.length" class="media-grid">
            <div v-for="asset in message.assets" :key="asset.id" class="media-item">
              <button
                class="media-preview-button"
                type="button"
                :data-testid="`open-chat-image-${asset.id}`"
                :aria-label="`放大查看${message.text || message.message_type}`"
                @click="openImagePreview(asset.url, message.text || message.message_type)"
              >
                <img :src="scopedAssetUrl(asset.url)" :alt="message.text || message.message_type" loading="lazy" />
              </button>
              <button
                class="media-delete-button"
                type="button"
                :disabled="deleting || deletingAssetId !== null"
                :data-testid="`delete-chat-image-${asset.id}`"
                aria-label="删除这张聊天截图"
                @click="deleteImage(message, asset.id)"
              >{{ deletingAssetId === asset.id ? '删除中…' : '删除图片' }}</button>
            </div>
          </div>
        </article>
      </div>
      <nav v-if="selected" class="capture-pager" aria-label="聊天消息分页">
        <span>共 {{ total }} 条 · 每页 {{ pageSize }} 条</span>
        <button type="button" data-testid="chat-page-prev" :disabled="page <= 1 || loading || deleting || deletingAssetId !== null" @click="changePage(page - 1)">上一页</button>
        <span aria-live="polite">{{ page }} / {{ totalPages }}</span>
        <button type="button" data-testid="chat-page-next" :disabled="page >= totalPages || loading || deleting || deletingAssetId !== null" @click="changePage(page + 1)">下一页</button>
      </nav>
    </section>
  </div>

  <div
    v-if="previewImage"
    class="image-preview-overlay"
    role="dialog"
    aria-modal="true"
    aria-label="图片预览"
    data-testid="chat-image-preview"
    @click.self="closeImagePreview"
  >
    <button
      class="image-preview-close"
      type="button"
      aria-label="关闭图片预览"
      data-testid="close-chat-image-preview"
      @click="closeImagePreview"
    >×</button>
    <img
      class="image-preview-image"
      :src="previewImage.src"
      :alt="previewImage.alt"
      data-testid="chat-image-preview-image"
    />
  </div>
</template>

<style scoped>
.capture-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }
.capture-stats .stat { display: flex; align-items: baseline; gap: 8px; padding: 10px 14px; }
.capture-stats .num { font-size: 22px; }
.capture-stats .label { margin: 0; }
.capture-layout { display: grid; grid-template-columns: 210px minmax(0, 1fr); align-items: start; gap: 12px; }
.conversation-panel, .timeline-panel { min-width: 0; padding: 14px; margin-bottom: 0; }
.conversation-panel { position: sticky; top: 12px; }
.conversation-list { max-height: calc(100vh - 170px); overflow-y: auto; }
.conversation { width: 100%; display: grid; gap: 3px; padding: 9px 10px; border: 0; border-bottom: 1px solid #f1f2f6; background: transparent; text-align: left; cursor: pointer; }
.conversation:hover, .conversation.selected { background: #f1f3ff; }
.conversation.selected { box-shadow: inset 3px 0 #3742fa; }
.conversation-title { color: #2f3542; font-weight: 600; overflow-wrap: anywhere; }
.conversation-meta, .conversation-time { color: #747d8c; font-size: 12px; }
.timeline-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }
.timeline-title { min-width: 0; }
.timeline-header h3 { margin: 0 0 4px; overflow-wrap: anywhere; }
.timeline-summary { color: #747d8c; font-size: 12px; }
.timeline-header select { max-width: 100%; padding: 6px 8px; border: 1px solid #dfe4ea; border-radius: 6px; background: #fff; }
.timeline-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.delete-button { padding: 7px 10px; border: 1px solid #ff6b81; border-radius: 6px; background: #fff; color: #c0392b; cursor: pointer; }
.delete-button:hover:not(:disabled) { background: #fff0f2; }
button:disabled { cursor: not-allowed; opacity: .5; }
.timeline { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 180px), 1fr)); align-items: start; gap: 10px; }
.message { min-width: 0; padding: 10px; border: 1px solid #e9ecf2; border-radius: 8px; background: #f8f9fc; }
.message:not(.has-media) { grid-column: 1 / -1; }
.message.outgoing { background: #f1f3ff; }
.message.system { background: #fffcf5; }
.message-head { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; color: #747d8c; font-size: 12px; overflow-wrap: anywhere; }
.message-head > span:first-child { width: 100%; color: #2f3542; }
.message-head time { font-size: 11px; }
.badge { padding: 1px 5px; border-radius: 4px; background: rgba(55, 66, 250, .08); color: #3742fa; }
.message-text { margin: 6px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.5; }
.media-grid { display: grid; gap: 8px; margin-top: 8px; }
.media-item { display: grid; min-width: 0; gap: 5px; }
.media-preview-button { width: 100%; padding: 0; border: 0; border-radius: 6px; background: #edf0f5; cursor: zoom-in; }
.media-grid img { display: block; width: 100%; height: 220px; object-fit: contain; border-radius: 6px; }
.media-delete-button { justify-self: end; padding: 3px 7px; border: 1px solid #ff6b81; border-radius: 5px; background: #fff; color: #c0392b; cursor: pointer; font-size: 12px; }
.capture-pager { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 14px; color: #747d8c; font-size: 13px; }
.capture-pager > span:first-child { margin-right: auto; }
.capture-pager button, .error button { padding: 5px 10px; border: 1px solid #dfe4ea; border-radius: 6px; background: #fff; cursor: pointer; }
.image-preview-overlay { position: fixed; inset: 0; z-index: 1000; display: flex; align-items: center; justify-content: center; padding: 32px; background: rgba(15, 18, 28, .82); }
.image-preview-image { display: block; max-width: 92vw; max-height: 90vh; object-fit: contain; border-radius: 8px; box-shadow: 0 16px 48px rgba(0, 0, 0, .35); }
.image-preview-close { position: fixed; top: 18px; right: 22px; width: 42px; height: 42px; border: 0; border-radius: 50%; background: rgba(255, 255, 255, .92); color: #2f3542; font-size: 30px; line-height: 1; cursor: pointer; }
.error { padding: 10px 14px; margin-bottom: 16px; border-radius: 6px; background: #fff0f0; color: #c0392b; }
@media (max-width: 1000px) {
  .capture-layout { grid-template-columns: 1fr; }
  .conversation-panel { position: static; }
  .conversation-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 180px), 1fr)); max-height: 150px; }
}
@media (max-width: 600px) {
  .capture-stats .stat { flex-wrap: wrap; gap: 2px 6px; padding: 8px; }
  .capture-stats .label { font-size: 11px; }
  .conversation-panel, .timeline-panel { padding: 10px; }
  .image-preview-overlay { padding: 16px; }
}
</style>
