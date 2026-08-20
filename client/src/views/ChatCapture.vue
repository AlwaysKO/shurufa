<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  api,
  type ChatCaptureOverview,
  type ChatConversationRow,
  type ChatMessageRow,
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
const loading = ref(false);
const error = ref('');

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

async function selectConversation(conversation: ChatConversationRow) {
  selected.value = conversation;
  messageType.value = 'all';
  error.value = '';
  loading.value = true;
  try {
    const result = await api.chatMessages(conversation.id);
    messages.value = result.messages;
  } catch (reason) {
    error.value = `加载消息失败：${(reason as Error).message}`;
  } finally {
    loading.value = false;
  }
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const [overviewResult, conversationResult] = await Promise.all([
      api.chatCaptureOverview(),
      api.chatConversations(),
    ]);
    overview.value = overviewResult;
    conversations.value = conversationResult.conversations;
    if (conversations.value[0]) await selectConversation(conversations.value[0]);
  } catch (reason) {
    error.value = `加载采集数据失败：${(reason as Error).message}`;
  } finally {
    loading.value = false;
  }
}

onMounted(load);
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
      <p v-if="conversations.length === 0" class="empty">暂无采集会话</p>
      <button
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation"
        :class="{ selected: selected?.id === conversation.id }"
        @click="selectConversation(conversation)"
      >
        <span class="conversation-title">{{ conversation.display_name || conversation.external_key }}</span>
        <span class="conversation-meta">
          {{ platformNames[conversation.platform] }} · {{ conversation.message_count }} 条
        </span>
        <span class="conversation-time">{{ formatTime(conversation.last_message_at) }}</span>
      </button>
    </section>

    <section class="card timeline-panel">
      <div class="timeline-header">
        <h3>{{ selected?.display_name || '消息时间线' }}</h3>
        <select v-model="messageType" aria-label="消息类型筛选">
          <option value="all">全部类型</option>
          <option v-for="type in messageTypes" :key="type" :value="type">{{ type }}</option>
        </select>
      </div>

      <p v-if="loading" class="empty">加载中…</p>
      <p v-else-if="visibleMessages.length === 0" class="empty">暂无消息</p>
      <div v-else class="timeline">
        <article
          v-for="message in visibleMessages"
          :key="message.id"
          class="message"
          :class="message.direction"
        >
          <div class="message-head">
            <span>{{ message.sender_name || message.sender_key }}</span>
            <span class="badge">{{ directionNames[message.direction] }}</span>
            <span class="badge">{{ message.message_type }}</span>
            <time>{{ formatTime(message.occurred_at || message.captured_at) }}</time>
          </div>
          <p v-if="message.text" class="message-text">{{ message.text }}</p>
          <div v-if="message.assets.length" class="media-grid">
            <a v-for="asset in message.assets" :key="asset.id" :href="asset.url" target="_blank">
              <img :src="asset.url" :alt="message.text || message.message_type" loading="lazy" />
            </a>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.capture-stats { margin-bottom: 20px; }
.capture-layout { display: grid; grid-template-columns: minmax(220px, 300px) minmax(0, 1fr); gap: 20px; }
.conversation-panel, .timeline-panel { margin-bottom: 0; }
.conversation { width: 100%; display: grid; gap: 4px; padding: 12px; border: 0; border-bottom: 1px solid #f1f2f6; background: transparent; text-align: left; cursor: pointer; }
.conversation:hover, .conversation.selected { background: #f1f3ff; }
.conversation.selected { box-shadow: inset 3px 0 #3742fa; }
.conversation-title { color: #2f3542; font-weight: 600; }
.conversation-meta, .conversation-time { color: #747d8c; font-size: 12px; }
.timeline-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.timeline-header h3 { margin: 0; }
.timeline-header select { padding: 6px 10px; border: 1px solid #dfe4ea; border-radius: 6px; background: #fff; }
.timeline { display: grid; gap: 12px; }
.message { max-width: 82%; padding: 12px 14px; border-radius: 10px; background: #f1f2f6; }
.message.outgoing { justify-self: end; background: #e9edff; }
.message.system { justify-self: center; background: #fff7e6; }
.message-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; color: #747d8c; font-size: 12px; }
.message-head time { margin-left: auto; }
.badge { padding: 2px 6px; border-radius: 10px; background: rgba(55, 66, 250, .1); color: #3742fa; }
.message-text { margin-top: 8px; white-space: pre-wrap; word-break: break-word; line-height: 1.6; }
.media-grid { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.media-grid img { width: 120px; height: 100px; object-fit: contain; border-radius: 6px; background: #fff; }
.error { padding: 10px 14px; margin-bottom: 16px; border-radius: 6px; background: #fff0f0; color: #c0392b; }
@media (max-width: 820px) { .capture-layout { grid-template-columns: 1fr; } .message { max-width: 100%; } }
</style>
