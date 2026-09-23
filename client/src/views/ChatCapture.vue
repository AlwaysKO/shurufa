<script setup lang="ts">
import { useConfirmation } from '../confirmation';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  api, currentUserId,
  type ChatMessageAsset,
  type ChatImageTarget,
  type ChatCaptureOverview,
  type ChatConversationRow,
  type ChatMessageRow,
  scopedAssetUrl,
} from '../api';

const askConfirmation = useConfirmation();

const overview = ref<ChatCaptureOverview>({
  conversation_count: 0,
  message_count: 0,
  media_count: 0,
});
type ChatPlatform = ChatConversationRow['platform'];
type SavedSelection = { platform: ChatPlatform; conversations: Partial<Record<ChatPlatform, number>>; groups: Partial<Record<ChatPlatform, string>> };
function readSelection(): SavedSelection {
  try {
    const saved = JSON.parse(globalThis.localStorage?.getItem(`chat-capture-selection:${currentUserId.value}`) || '{}');
    const valid = ['wechat', 'qq', 'douyin'];
    return { platform: valid.includes(saved.platform) ? saved.platform : 'wechat', conversations: Object.fromEntries(
      Object.entries(saved.conversations || {}).filter(([key, id]) => valid.includes(key) && Number.isSafeInteger(id) && (Number(id) > 0 || id === -1)),
    ), groups: Object.fromEntries(Object.entries(saved.groups || {}).filter(([key, name]) => valid.includes(key) && typeof name === 'string' && name.length > 0 && name.length <= 500)) };
  } catch { return { platform: 'wechat', conversations: {}, groups: {} }; }
}
let savedSelection = readSelection();
const platform = ref<ChatPlatform>(savedSelection.platform);
function rememberSelection() {
  savedSelection.platform = platform.value;
  if (selected.value) savedSelection.conversations[platform.value] = selected.value.id;
  else delete savedSelection.conversations[platform.value];
  if (selected.value?.group_name) savedSelection.groups[platform.value] = selected.value.group_name;
  else delete savedSelection.groups[platform.value];
  try { globalThis.localStorage?.setItem(`chat-capture-selection:${currentUserId.value}`, JSON.stringify(savedSelection)); } catch { /* 存储被禁用不影响页面 */ }
}
let latestLoad = 0;
const conversations = ref<ChatConversationRow[]>([]);
const selected = ref<ChatConversationRow | null>(null);
const pendingGroup = computed(() => selected.value?.id === -1);
const multipleSources = computed(() => !!selected.value?.is_name_group && (
  (selected.value.source_count ?? 1) > 1 || messages.value.some(message => message.conversation_id && message.conversation_id !== selected.value?.id)
));
const groupScopeArgs = computed((): [ChatPlatform?, string?] => selected.value?.is_name_group && selected.value.group_name
  ? [platform.value, selected.value.group_name] : pendingGroup.value ? [platform.value] : []);
function displayName(conversation: ChatConversationRow | null) {
  if (!conversation) return '';
  if (/^screenshot-v2:truncated:[a-f0-9-]{36}$/.test(conversation.external_key)) return conversation.display_name || conversation.external_key;
  return conversation.is_pending_group || conversation.is_pending_source || conversation.display_name?.startsWith('待确认')
    ? '待确认会话' : conversation.display_name || conversation.external_key;
}
const messages = ref<ChatMessageRow[]>([]);
const messageType = ref('all');
const page = ref(1);
const pageSize = 20;
const total = ref(0);
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const pageJump = ref<number | string>(1);
watch(page, value => { pageJump.value = value; });
const validPageJump = computed(() => Number.isSafeInteger(Number(pageJump.value)) && Number(pageJump.value) >= 1);
const pageLinks = computed(() => {
  const values = new Set([1, totalPages.value]);
  for (let n = Math.max(1, page.value - 2); n <= Math.min(totalPages.value, page.value + 2); n++) values.add(n);
  const links: Array<number | string> = []; let previous = 0;
  for (const n of [...values].sort((a,b) => a-b)) {
    if (previous && n - previous > 1) links.push(`gap-${previous}`);
    links.push(n); previous = n;
  }
  return links;
});
function jumpToPage() { if (validPageJump.value) void changePage(Number(pageJump.value)); }
const messageError = ref('');
let latestRequest = 0;
let disposed = false;
const loading = ref(false);
const error = ref('');
const deleting = ref(false);
const deletingAssetId = ref<number | null>(null);
const bulkDeleting = ref(false);
const deletingConversations = ref(false);
const confirming = ref(false);
const merging = ref(false);
const mutationBusy = computed(() => merging.value || deleting.value || deletingAssetId.value !== null || bulkDeleting.value || deletingConversations.value || confirming.value);
const selectedImageKeys = ref<string[]>([]);
const deleteNotice = ref('');
const selectedConversationIds = ref<number[]>([]);
const selectableConversations = computed(() => conversations.value.filter(item => item.id > 0 && !item.is_pending_group));
const selectedConversations = computed(() => selectableConversations.value.filter(item => selectedConversationIds.value.includes(item.id)));
let conversationScopeVersion = 0;
watch([currentUserId, platform], () => {
  conversationScopeVersion++;
  selectedConversationIds.value = [];
  deleteNotice.value = '';
}, { flush: 'sync' });
watch(conversations, () => {
  const visibleIds = new Set(selectableConversations.value.map(item => item.id));
  selectedConversationIds.value = selectedConversationIds.value.filter(id => visibleIds.has(id));
});
function selectListedConversations() {
  if (!loading.value && !mutationBusy.value) selectedConversationIds.value = selectableConversations.value.map(item => item.id);
}
function toggleConversationSelection(conversation: ChatConversationRow, checked: boolean) {
  if (loading.value || mutationBusy.value || conversation.id <= 0 || conversation.is_pending_group) return;
  selectedConversationIds.value = checked
    ? [...new Set([...selectedConversationIds.value, conversation.id])]
    : selectedConversationIds.value.filter(id => id !== conversation.id);
}
const previewImage = ref<(ChatImageTarget & { src: string; alt: string; ordinal?: number; total?: number }) | null>(null);
const previewEl = ref<HTMLDivElement | null>(null);
const previewLoading = ref(false);
const previewError = ref('');
const previewBoundary = ref<'next' | 'previous' | null>(null);
let previewRequest = 0;
let previousFocus: HTMLElement | null = null;
let swipeStart: { id: number; x: number; y: number } | null = null;
const previewScope = computed(() => JSON.stringify([currentUserId.value, platform.value, selected.value?.id, selected.value?.group_name]));
const selectionContext = computed(() => JSON.stringify([previewScope.value, page.value, messageType.value]));
watch(selectionContext, () => { clearImageSelection(); closeImagePreview(); }, { flush: 'sync' });

const platformNames = { wechat: '微信', qq: 'QQ', douyin: '抖音' } as const;
const directionNames = { incoming: '收到', outgoing: '发送', system: '系统' } as const;
const pendingReasonNames = {
  title_unreadable: '未读到标题', title_unconfirmed: '读到标题，尚未确认',
  possible_existing_conversation: '疑似已有会话', non_chat_page: '非聊天页面',
} as const;
const messageTypes = computed(() => [
  ...new Set(messages.value.map((message) => message.message_type)),
]);
const visibleMessages = computed(() => messageType.value === 'all'
  ? messages.value
  : messages.value.filter((message) => message.message_type === messageType.value));

const imageKey = (messageId: string, assetId: number) => `${messageId}:${assetId}`;
const isImage = (asset: ChatMessageAsset) => asset.mime_type.startsWith('image/');
const visibleImages = computed(() => {
  const seen = new Set<string>();
  return visibleMessages.value.flatMap(message => message.assets.filter(isImage).map(asset => ({
    message_id: message.id, asset_id: asset.id,
  }))).filter(image => {
    const key = imageKey(image.message_id, image.asset_id);
    if (seen.has(key)) return false;
    seen.add(key); return true;
  });
});
const selectedImages = computed(() => visibleImages.value.filter(image => selectedImageKeys.value.includes(imageKey(image.message_id, image.asset_id))));
function clearImageSelection() { selectedImageKeys.value = []; }
function selectPageImages() {
  if (!loading.value && !mutationBusy.value) selectedImageKeys.value = visibleImages.value.map(image => imageKey(image.message_id, image.asset_id));
}
async function confirmAction(message: string, options?: {title: string; confirmText: string}) {
  if (confirming.value) return false;
  confirming.value = true;
  try { return await askConfirmation(message, options); } finally { confirming.value = false; }
}

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
  if ((message.metadata.capture_source === 'wechat_empty_tree_screenshot' ||
      message.metadata.capture_kind === 'conversation_screenshot') && selected.value) {
    const chatName = selected.value.display_name || selected.value.external_key;
    return `${chatName} ${formatImageLabelTime(message.captured_at)}`;
  }
  return message.sender_name || message.sender_key;
}

async function openImagePreview(message: ChatMessageRow, asset: ChatMessageAsset) {
  if (loading.value || mutationBusy.value || !isImage(asset)) return;
  ++previewRequest;
  previewLoading.value = false; previewError.value = ''; previewBoundary.value = null;
  previousFocus = typeof document === 'undefined' ? null : document.activeElement as HTMLElement | null;
  previewImage.value = { message_id: message.id, asset_id: asset.id, src: scopedAssetUrl(asset.url), alt: message.text || message.message_type };
  await nextTick();
  previewEl.value?.focus();
}

function closeImagePreview() {
  ++previewRequest;
  previewImage.value = null;
  previewLoading.value = false; previewError.value = ''; previewBoundary.value = null;
  swipeStart = null;
  const focus = previousFocus;
  previousFocus = null;
  void nextTick(() => { if (focus?.isConnected) focus.focus(); });
}

async function navigateImage(direction: 'next' | 'previous') {
  const current = previewImage.value, conversationId = selected.value?.id;
  if (!current || !conversationId || previewLoading.value || previewBoundary.value === direction) return;
  // 导航按钮马上会禁用；先把焦点留在弹层，避免浏览器将焦点退回body导致Esc失效。
  previewEl.value?.focus();
  const version = ++previewRequest, scope = previewScope.value;
  previewLoading.value = true; previewError.value = '';
  try {
    const result = await api.chatAdjacentImage(conversationId, current.message_id, current.asset_id, direction, ...groupScopeArgs.value);
    if (disposed || version !== previewRequest || scope !== previewScope.value || !previewImage.value) return;
    if (!result.image) {
      previewBoundary.value = direction;
      previewError.value = direction === 'next' ? '已到最后一张图片' : '已到第一张图片';
      return;
    }
    const image = result.image;
    previewImage.value = { ...image, src: scopedAssetUrl(image.url) };
    previewBoundary.value = image.ordinal === 1 ? 'previous' : image.ordinal === image.total ? 'next' : null;
  } catch (reason) {
    if (!disposed && version === previewRequest) previewError.value = `切换失败，可重试：${(reason as Error).message}`;
  } finally { if (version === previewRequest) previewLoading.value = false; }
}

function previewKeydown(event: KeyboardEvent) {
  if (event.isComposing || event.repeat) return;
  if (['Escape', 'ArrowLeft', 'ArrowRight'].includes(event.key)) {
    event.preventDefault(); event.stopPropagation();
    if (event.key === 'Escape') closeImagePreview();
    else void navigateImage(event.key === 'ArrowLeft' ? 'previous' : 'next');
  } else if (event.key === 'Tab' && previewEl.value) {
    const buttons = [...previewEl.value.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
    const first = buttons[0], last = buttons[buttons.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === previewEl.value)) {
      event.preventDefault(); last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
}
function startSwipe(event: PointerEvent) {
  if (event.isPrimary === false || event.button !== 0) { swipeStart = null; return; }
  previewEl.value?.focus();
  (event.currentTarget as HTMLElement | null)?.setPointerCapture?.(event.pointerId);
  swipeStart = { id: event.pointerId, x: event.clientX, y: event.clientY };
}
function endSwipe(event: PointerEvent) {
  const start = swipeStart; swipeStart = null;
  if (!start || start.id !== event.pointerId) return;
  const dx = event.clientX - start.x, dy = event.clientY - start.y;
  if (Math.abs(dx) >= 50 && Math.abs(dx) > Math.abs(dy) * 1.3) void navigateImage(dx < 0 ? 'next' : 'previous');
}

async function loadMessages() {
  const conversation = selected.value;
  if (!conversation || disposed) return;
  clearImageSelection();
  closeImagePreview();
  const request = ++latestRequest;
  messageError.value = '';
  messages.value = [];
  loading.value = true;
  try {
    // 服务端先按 captured_at DESC、id DESC 排序，再分页；不对当前页单独排序。
    const result = await api.chatMessages(conversation.id, page.value, pageSize, ...groupScopeArgs.value);
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
  if (mutationBusy.value) return;
  selected.value = conversation;
  rememberSelection();
  error.value = '';
  page.value = 1;
  total.value = 0;
  messageType.value = 'all';
  await loadMessages();
}

async function changePage(next: number) {
  if (loading.value || mutationBusy.value || !Number.isSafeInteger(next)) return;
  page.value = Math.min(totalPages.value, Math.max(1, next));
  messageType.value = 'all';
  await loadMessages();
}

async function selectPlatform(next: ChatConversationRow['platform']) {
  if (next === platform.value || mutationBusy.value) return;
  platform.value = next;
  savedSelection.platform = next;
  try { globalThis.localStorage?.setItem(`chat-capture-selection:${currentUserId.value}`, JSON.stringify(savedSelection)); } catch { /* 存储禁用时继续 */ }
  latestRequest += 1;
  selected.value = null;
  conversations.value = [];
  messages.value = [];
  closeImagePreview();
  page.value = 1;
  total.value = 0;
  messageType.value = 'all';
  overview.value = { conversation_count: 0, message_count: 0, media_count: 0 };
  await load();
}

async function load() {
  const request = ++latestLoad;
  loading.value = true;
  error.value = '';
  messageError.value = '';
  try {
    const [overviewResult, conversationResult] = await Promise.all([
      api.chatCaptureOverview(platform.value),
      api.chatConversations(1, 100, platform.value, '', true, true),
    ]);
    if (disposed || request !== latestLoad) return;
    // 会话按列表的同名/待确认分组统计，不能混用底层来源数或当前页条数。
    overview.value = { ...overviewResult, conversation_count: conversationResult.total };
    conversations.value = conversationResult.conversations;
    const rememberedId = selected.value?.id ?? savedSelection.conversations[platform.value];
    const rememberedName = selected.value?.group_name ?? savedSelection.groups[platform.value];
    let restored = rememberedName
      ? conversations.value.find(item => item.group_name === rememberedName) ?? null
      : conversations.value.find(item => item.id === rememberedId) ?? null;
    if (rememberedName && !restored) {
      restored = (await api.chatConversationGroup(platform.value, rememberedName)).conversation;
      if (disposed || request !== latestLoad) return;
    }
    if (rememberedId && rememberedId > 0 && !restored) {
      restored = (await api.resolveChatConversation(rememberedId)).conversation;
      if (disposed || request !== latestLoad) return;
      if (restored && restored.platform !== platform.value) restored = null;
      const legacyPending = restored && (restored.display_name?.startsWith('待确认') || (restored.identity_confidence < 0.8 && (
          /^(?:screenshot-v2:|capture-v3:|screenshot-pending:|capture-pending:|notification-v2:|header:)/.test(restored.external_key) ||
          /^(?:微信会话)/.test(restored.display_name || ''))));
      if (restored && (restored.is_pending_source ?? legacyPending)) {
        restored = conversations.value.find(item => item.id === -1) ?? null;
      } else if (restored?.display_name?.trim() && !/^screenshot-v2:truncated:[a-f0-9-]{36}$/.test(restored.external_key)) {
        const name = restored.display_name.trim();
        const group = conversations.value.find(item => item.group_name === name);
        if (group) restored = group;
        else if (restored.is_pending_source === false) {
          restored = (await api.chatConversationGroup(platform.value, name)).conversation;
          if (disposed || request !== latestLoad) return;
        }
      }
    }
    if (restored && !conversations.value.some(item => item.id === restored!.id)) conversations.value.unshift(restored);
    selected.value = restored ?? conversations.value[0] ?? null;
    rememberSelection();
    page.value = 1;
    total.value = 0;
    messageType.value = 'all';
    if (selected.value) await loadMessages();
    else loading.value = false;
  } catch (reason) {
    if (disposed || request !== latestLoad) return;
    error.value = `加载采集数据失败：${(reason as Error).message}`;
    loading.value = false;
  }
}

async function deleteSelectedConversation() {
  const conversation = selected.value;
  if (!conversation || pendingGroup.value || loading.value || mutationBusy.value) return;
  const context = selectionContext.value;
  const name = displayName(conversation);
  const sourceNotice = conversation.is_name_group ? `（包含 ${conversation.source_count ?? 1} 个同名来源）` : '';
  if (!(await confirmAction(`确定删除“${name}”${sourceNotice}及其全部聊天记录吗？此操作不可恢复。`))) return;
  if (loading.value || mutationBusy.value || disposed || context !== selectionContext.value) return;
  deleting.value = true;
  error.value = '';
  try {
    if (conversation.is_name_group && conversation.group_name) {
      const result = await api.deleteChatConversationGroup({ confirm: 'DELETE', platform: platform.value,
        group_name: conversation.group_name, source_ids: conversation.source_ids ?? [] });
      if (result.files_pending) deleteNotice.value = '会话已删除；附件文件清理将在后台重试。';
    } else await api.deleteChatConversation(conversation.id);
    selected.value = null;
    rememberSelection();
    messages.value = [];
    await load();
  } catch (reason) {
    error.value = `删除会话失败：${(reason as Error).message}`;
  } finally {
    deleting.value = false;
  }
}

async function deleteSelectedConversations() {
  if (loading.value || mutationBusy.value || !selectedConversations.value.length) return;
  const version = conversationScopeVersion, listVersion = latestLoad, app = platform.value;
  const targets = selectedConversations.value.map(conversation => ({ ...conversation, source_ids: [...(conversation.source_ids ?? [])] }));
  const messageCount = targets.reduce((sum, conversation) => sum + conversation.message_count, 0);
  const names = targets.map(conversation => `“${displayName(conversation)}”`).join('、');
  if (!(await confirmAction(`确定删除选中的 ${targets.length} 个会话及其全部聊天记录吗？所选会话当前共 ${messageCount} 条消息。\n${names}\n同名会话包含列表中已显示的全部来源，关联关系资料也会删除。此操作不可恢复。`,
    { title: '批量删除会话', confirmText: `删除 ${targets.length} 个会话` }))) return;
  if (disposed || version !== conversationScopeVersion || listVersion !== latestLoad || loading.value || mutationBusy.value) return;
  deletingConversations.value = true;
  error.value = ''; deleteNotice.value = '';
  try {
    const result = await api.deleteChatConversations({ confirm: 'DELETE', platform: app, conversations: targets.map(conversation =>
      conversation.is_name_group && conversation.group_name
        ? { group_name: conversation.group_name, source_ids: conversation.source_ids }
        : { id: conversation.id }) });
    if (disposed || version !== conversationScopeVersion) return;
    selectedConversationIds.value = [];
    if (targets.some(conversation => conversation.id === selected.value?.id)) {
      selected.value = null;
      rememberSelection();
      messages.value = [];
    }
    await load();
    if (disposed || version !== conversationScopeVersion) return;
    deleteNotice.value = `已删除 ${result.deleted_conversations} 个会话${result.files_pending ? '；附件文件清理将在后台重试。' : '。'}`;
  } catch (reason) {
    if (!disposed && version === conversationScopeVersion) error.value = `批量删除会话失败：${(reason as Error).message}`;
  } finally { deletingConversations.value = false; }
}

// 列表只有首页；不能因当前组未出现在前100项中，就把已恢复的选中组丢掉。
async function refreshAfterImageDeletion(conversation: ChatConversationRow | null, scope: string) {
  if (disposed || scope !== previewScope.value) return;
  const [overviewResult, conversationResult] = await Promise.all([
    api.chatCaptureOverview(platform.value), api.chatConversations(1, 100, platform.value, '', true, true),
  ]);
  if (disposed || scope !== previewScope.value) return;
  const rows = conversationResult.conversations;
  let restored = conversation?.group_name
    ? rows.find(item => item.group_name === conversation.group_name) ?? null
    : rows.find(item => item.id === conversation?.id) ?? null;
  if (!restored && conversation?.is_name_group && conversation.group_name) {
    restored = (await api.chatConversationGroup(platform.value, conversation.group_name)).conversation;
    if (disposed || scope !== previewScope.value) return;
    if (restored) rows.unshift(restored);
  }
  overview.value = { ...overviewResult, conversation_count: conversationResult.total };
  conversations.value = rows; selected.value = restored;
  rememberSelection();
  if (restored) await loadMessages();
  else { messages.value = []; total.value = 0; page.value = 1; }
}

async function deleteImage(message: ChatMessageRow, assetId: number) {
  if (loading.value || mutationBusy.value) return;
  const context = selectionContext.value;
  if (!(await confirmAction('确定删除这张聊天截图吗？此操作不可恢复。'))) return;
  if (loading.value || mutationBusy.value || disposed || context !== selectionContext.value) return;
  deletingAssetId.value = assetId;
  error.value = '';
  try {
    await api.deleteChatImage(message.id, assetId);
    if (disposed || context !== selectionContext.value) return;
    await refreshAfterImageDeletion(selected.value, previewScope.value);
  } catch (reason) {
    error.value = `删除图片失败：${(reason as Error).message}`;
  } finally {
    deletingAssetId.value = null;
  }
}

async function deleteSelectedImages() {
  const conversation = selected.value;
  if (!conversation || loading.value || mutationBusy.value || !selectedImages.value.length || disposed) return;
  const images = selectedImages.value.map(image => ({ ...image }));
  const context = selectionContext.value, version = latestRequest;
  const selection = selectedImageKeys.value.join(',');
  if (images.length > 1000) { error.value = '每批最多删除1000张图片，请减少选择后重试'; return; }
  if (!(await confirmAction(`确定永久删除选中的 ${images.length} 张图片吗？\n仅删除当前会话中选中的图片，保留文字和未选图片。纯图片记录删空后会移除空记录。\n此操作不可恢复，仅影响当前后台，不删除手机上的图片。`))) return;
  if (disposed || mutationBusy.value || loading.value || context !== selectionContext.value || version !== latestRequest || selection !== selectedImageKeys.value.join(',')) return;
  bulkDeleting.value = true; error.value = ''; deleteNotice.value = '';
  let completed = false;
  try {
    const result = await api.deleteChatImages({ confirm: 'DELETE', conversation_id: conversation.id, images, ...(pendingGroup.value || conversation.is_name_group ? { platform: platform.value } : {}), ...(conversation.is_name_group && conversation.group_name ? { group_name: conversation.group_name } : {}) });
    completed = true;
    if (disposed || context !== selectionContext.value) return;
    deleteNotice.value = `已删除 ${result.deleted_images} 张图片${result.files_pending ? '；附件文件清理将在后台重试。' : ''}`;
    await refreshAfterImageDeletion(conversation, previewScope.value);
  } catch (reason) {
    if (!disposed && context === selectionContext.value) error.value = `${completed ? '图片已删除，但统计刷新失败' : '删除图片失败'}：${(reason as Error).message}`;
  } finally { bulkDeleting.value = false; }
}

const mergeOpen = ref(false);
const mergeSource = ref<ChatConversationRow | null>(null);
const mergeQuery = ref('');
const mergeTargets = ref<ChatConversationRow[]>([]);
const mergePage = ref(1), mergeTotal = ref(0), mergeLoading = ref(false);
const mergeError = ref('');
let mergeRequest = 0;
watch(previewScope, () => { mergeOpen.value = false; mergeRequest++; mergeTargets.value = []; });
async function searchMergeTargets(next = 1) {
  if (mutationBusy.value || !selected.value) return;
  const token = ++mergeRequest, scope = previewScope.value;
  mergeLoading.value = true; mergeError.value = ''; mergeTargets.value = [];
  try {
    const result = await api.chatConversations(next, 20, platform.value, mergeQuery.value, true, true);
    if (disposed || token !== mergeRequest || scope !== previewScope.value) return;
    mergeTargets.value = result.conversations.filter(item => item.id > 0 && item.id !== mergeSource.value?.id && !item.source_ids?.includes(mergeSource.value?.id ?? 0));
    mergeTotal.value = result.total; mergePage.value = next;
  } catch (reason) { if (token === mergeRequest) mergeError.value = (reason as Error).message; }
  finally { if (token === mergeRequest) mergeLoading.value = false; }
}
async function openMerge() {
  if (mutationBusy.value || loading.value || !selected.value || pendingGroup.value || multipleSources.value) return;
  mergeSource.value = selected.value;
  mergeOpen.value = true; mergeQuery.value = ''; await searchMergeTargets();
}
async function confirmSource(message: ChatMessageRow) {
  if ((!pendingGroup.value && !multipleSources.value) || !message.conversation_id || mutationBusy.value || loading.value) return;
  const scope = previewScope.value;
  loading.value = true;
  try {
    const result = await api.resolveChatConversation(message.conversation_id);
    if (disposed || scope !== previewScope.value) return;
    if (!result.conversation || result.conversation.platform !== platform.value ||
        result.conversation.id !== message.conversation_id || (pendingGroup.value && !result.conversation.is_pending_source && !result.conversation.display_name?.startsWith('待确认') && result.conversation.identity_confidence >= 0.8)) throw Error('此来源已确认或已合并，请刷新后查看');
    mergeSource.value = result.conversation;
    mergeOpen.value = true; mergeQuery.value = message.pending_diagnostic?.observed_title ?? ''; await searchMergeTargets();
  } catch (reason) { if (scope === previewScope.value) error.value = (reason as Error).message; }
  finally { if (scope === previewScope.value) loading.value = false; }
}
async function mergeInto(target: ChatConversationRow) {
  const source = mergeSource.value, scope = previewScope.value;
  if (!source || mutationBusy.value || loading.value || mergeLoading.value) return;
  if (!(await confirmAction(`将“${displayName(source)}”合并到“${displayName(target)}”？\n保留全部图片和文字；原会话后续上报也归入目标。`, {title: '确认合并会话', confirmText: '确认合并'}))) return;
  if (disposed || scope !== previewScope.value || mutationBusy.value || loading.value) return;
  merging.value = true; mergeError.value = '';
  try {
    const result = await api.mergeChatConversation(source.id, target.id);
    if (disposed || scope !== previewScope.value) return;
    selected.value = null;
    savedSelection.conversations[platform.value] = result.target_id;
    if (target.group_name) savedSelection.groups[platform.value] = target.group_name;
    else delete savedSelection.groups[platform.value];
    // 成功即持久化目标；即使后续刷新失败，也不会误报为合并失败。
    try { globalThis.localStorage?.setItem(`chat-capture-selection:${currentUserId.value}`, JSON.stringify(savedSelection)); } catch { /* optional */ }
    mergeOpen.value = false;
    await load();
  } catch (reason) { if (!disposed && scope === previewScope.value) mergeError.value = (reason as Error).message; }
  finally { merging.value = false; }
}
async function chooseSuggestedSource(message: ChatMessageRow, targetId: number) {
  if (!pendingGroup.value || !message.conversation_id || loading.value || mutationBusy.value) return;
  const scope = previewScope.value;
  loading.value = true;
  let target: ChatConversationRow | null = null;
  try {
    const [sourceResult, targetResult] = await Promise.all([
      api.resolveChatConversation(message.conversation_id), api.resolveChatConversation(targetId),
    ]);
    if (disposed || scope !== previewScope.value) return;
    const source = sourceResult.conversation, candidate = targetResult.conversation;
    if (!source || !candidate || source.id !== message.conversation_id || !source.is_pending_source ||
        candidate.id !== targetId || candidate.is_pending_source || candidate.identity_confidence < .8 ||
        source.platform !== platform.value || candidate.platform !== source.platform || candidate.account_key !== source.account_key) {
      throw Error('来源或建议归属已变化，请刷新后重新选择');
    }
    mergeSource.value = source;
    mergeOpen.value = true;
    target = candidate;
  } catch (reason) {
    if (scope === previewScope.value) error.value = (reason as Error).message;
  } finally { if (scope === previewScope.value) loading.value = false; }
  if (target && !disposed && scope === previewScope.value) await mergeInto(target);
}
watch(currentUserId, () => {
  latestLoad++; latestRequest++; mergeRequest++;
  selected.value = null; conversations.value = []; messages.value = [];
  savedSelection = readSelection(); platform.value = savedSelection.platform;
  void load();
});
onMounted(load);
onBeforeUnmount(() => { closeImagePreview(); disposed = true; latestRequest += 1; latestLoad += 1; });
</script>

<template>
  <div :inert="previewImage ? true : undefined">
  <nav class="platform-tabs" role="tablist" aria-label="聊天采集 App">
    <button
      v-for="(name, key) in platformNames" :key="key"
      type="button" role="tab" :aria-selected="platform === key"
      :data-testid="`chat-tab-${key}`" :class="{ active: platform === key }"
      :disabled="mutationBusy"
      @click="selectPlatform(key)"
    >{{ name }}</button>
  </nav>
  <div class="stat-grid capture-stats">
    <div class="stat"><div class="num">{{ overview.conversation_count }}</div><div class="label">会话</div></div>
    <div class="stat"><div class="num">{{ overview.message_count }}</div><div class="label">消息</div></div>
    <div class="stat"><div class="num">{{ overview.media_count }}</div><div class="label">媒体资源</div></div>
  </div>

  <p v-if="error" class="error">{{ error }}</p>

  <div class="capture-layout">
    <section class="card conversation-panel">
      <h3>会话列表</h3>
      <div class="conversation-bulk-actions">
        <span class="timeline-summary" role="status">已选 {{ selectedConversations.length }} 个会话</span>
        <button type="button" class="capture-action" data-testid="chat-select-listed-conversations" :disabled="loading || mutationBusy || !selectableConversations.length" @click="selectListedConversations">全选当前列表</button>
        <button type="button" class="capture-action" data-testid="chat-clear-conversation-selection" :disabled="loading || mutationBusy || !selectedConversations.length" @click="selectedConversationIds = []">清空选择</button>
        <button type="button" class="delete-button" data-testid="chat-delete-selected-conversations" :disabled="loading || mutationBusy || !selectedConversations.length" @click="deleteSelectedConversations">{{ deletingConversations ? '删除中…' : '删除选中会话' }}</button>
      </div>
      <div class="conversation-list">
        <p v-if="conversations.length === 0" class="empty">暂无采集会话</p>
        <div v-for="conversation in conversations" :key="conversation.id" class="conversation-row">
        <input type="checkbox" class="conversation-checkbox" :aria-label="`选择会话：${displayName(conversation)}`"
          :data-testid="`chat-select-conversation-${conversation.id}`"
          :checked="selectedConversationIds.includes(conversation.id)"
          :disabled="loading || mutationBusy || conversation.id <= 0 || conversation.is_pending_group === true"
          @change="toggleConversationSelection(conversation, ($event.target as HTMLInputElement).checked)" />
        <button
          class="conversation"
          :disabled="mutationBusy"
          :data-testid="`chat-conversation-${conversation.id}`"
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
      </div>
    </section>

    <section class="card timeline-panel">
      <div class="timeline-header">
        <div class="timeline-title">
          <h3>{{ selected?.display_name || selected?.external_key || '消息时间线' }}</h3>
          <span class="timeline-summary">共 {{ total }} 项 · 每页 {{ pageSize }} 项（图片逐张分页） · 采集时间倒序，最新在前</span>
        </div>
        <div class="timeline-actions">
          <button class="capture-action" data-testid="chat-open-merge" :disabled="!selected || pendingGroup || multipleSources || loading || mutationBusy" @click="openMerge">合并到…</button>
          <select v-model="messageType" aria-label="本页消息类型筛选" :disabled="loading || mutationBusy">
            <option value="all">本页全部类型</option>
            <option v-for="type in messageTypes" :key="type" :value="type">{{ type }}</option>
          </select>
          <button
            class="delete-button"
            data-testid="chat-delete-conversation"
            type="button"
            :disabled="!selected || pendingGroup || loading || mutationBusy"
            @click="deleteSelectedConversation"
          >{{ deleting ? '删除中…' : '删除会话' }}</button>
        </div>
      </div>

      <p v-if="multipleSources" class="timeline-summary">同名会话的全部图片集中展示，内部来源独立保留。更改归属请使用图片上的“确认此来源归属”。</p>
      <p v-if="pendingGroup" class="timeline-summary">未确认图片集中显示，来源仍独立保留。可逐来源确认归属，或选择图片删除；不会整组误合并。</p>
      <section v-if="mergeOpen" class="merge-panel" aria-label="选择合并目标">
        <h4>选择目标会话（同一手机、同一App）</h4>
        <p>当前来源：{{ displayName(mergeSource) }}（#{{ mergeSource?.id }}）。仅此来源的历史图片和文字归入目标，后续该来源上报也归入目标。</p>
        <form @submit.prevent="searchMergeTargets(1)">
          <input v-model="mergeQuery" aria-label="搜索目标会话" placeholder="搜索会话名称" :disabled="mutationBusy" />
          <button class="capture-action" :disabled="mutationBusy || mergeLoading">搜索</button>
          <button class="capture-action" type="button" :disabled="mutationBusy" @click="mergeOpen = false; mergeRequest++">取消</button>
        </form>
        <p v-if="mergeError" class="error-text" role="alert">{{ mergeError }}</p>
        <p v-if="mergeLoading">正在加载目标会话…</p>
        <button v-for="target in mergeTargets" :key="target.id" :data-testid="`chat-merge-target-${target.id}`" class="capture-action merge-target" :disabled="mutationBusy || mergeLoading" @click="mergeInto(target)">
          {{ target.display_name || target.external_key }} · {{ target.message_count }} 条 · #{{ target.id }}
        </button>
        <p v-if="!mergeLoading && !mergeTargets.length">没有可选目标</p>
        <div>
          <button class="capture-action" :disabled="mutationBusy || mergeLoading || mergePage <= 1" @click="searchMergeTargets(mergePage - 1)">上一页</button>
          <span> {{ mergePage }} / {{ Math.max(1, Math.ceil(mergeTotal / 20)) }} </span>
          <button class="capture-action" :disabled="mutationBusy || mergeLoading || mergePage * 20 >= mergeTotal" @click="searchMergeTargets(mergePage + 1)">下一页</button>
        </div>
      </section>
      <div class="image-selection-toolbar">
        <button data-testid="chat-select-page" :disabled="loading || mutationBusy || !visibleImages.length" @click="selectPageImages">全选本页</button>
        <button data-testid="chat-clear-selection" :disabled="mutationBusy || !selectedImageKeys.length" @click="clearImageSelection">全不选</button>
        <span data-testid="chat-selection-count">已选 {{ selectedImages.length }} 张（仅本页筛选结果）</span>
        <button class="delete-button" data-testid="chat-delete-selected" :disabled="loading || mutationBusy || !selectedImages.length" @click="deleteSelectedImages">{{ bulkDeleting ? '删除中…' : '删除选中图片' }}</button>
      </div>
      <p v-if="deleteNotice" class="delete-notice" role="status">{{ deleteNotice }}</p>
      <p v-if="messageError" class="error" role="alert">
        {{ messageError }}
        <button type="button" data-testid="chat-retry" :disabled="loading || mutationBusy" @click="loadMessages">重试</button>
      </p>
      <p v-if="loading" class="empty">加载中…</p>
      <p v-else-if="!messageError && visibleMessages.length === 0" class="empty">暂无消息</p>
      <div v-else-if="!messageError" class="timeline" data-testid="chat-gallery">
        <article
          v-for="message in visibleMessages"
          :key="`${message.id}:${message.assets[0]?.id ?? 'text'}`"
          class="message"
          :class="[message.direction, { 'has-media': message.assets.length > 0 }]"
        >
          <div class="message-head">
            <button class="capture-action capture-source-action" v-if="(pendingGroup || multipleSources) && message.conversation_id" :data-testid="`chat-confirm-source-${message.id}`" :disabled="loading || mutationBusy" @click="confirmSource(message)">确认此来源归属 #{{ message.conversation_id }}</button>
            <span :data-testid="`chat-image-label-${message.id}`">{{ messageDisplayName(message) }}</span>
            <span class="badge">{{ directionNames[message.direction] }}</span>
            <span class="badge">{{ message.message_type }}</span>
            <time :datetime="message.captured_at" title="采集时间">{{ formatTime(message.captured_at) }}</time>
          </div>
          <div v-if="pendingGroup && message.pending_diagnostic" class="pending-diagnostic">
            <strong>{{ message.pending_diagnostic.non_chat_evidence === 'title_only' ? '疑似非聊天页面，请看图确认' : pendingReasonNames[message.pending_diagnostic.reason] }}</strong>
            <p>观测标题：{{ message.pending_diagnostic.observed_title || '未读到' }}</p>
            <template v-if="message.pending_diagnostic.suggested_conversations.length">
              <p>仅名称相同，请核对截图后选择归属：</p>
              <button v-for="candidate in message.pending_diagnostic.suggested_conversations" :key="candidate.id"
                class="capture-action" :data-testid="`chat-suggested-target-${message.id}-${candidate.id}`"
                :disabled="loading || mutationBusy" @click="chooseSuggestedSource(message, candidate.id)">
                {{ candidate.display_name }} #{{ candidate.id }} · 选择此归属
              </button>
            </template>
          </div>
          <p v-if="message.text" class="message-text">{{ message.text }}</p>
          <div v-if="message.assets.length" class="media-grid">
            <div v-for="asset in message.assets" :key="asset.id + ':' + asset.role" class="media-item">
              <label v-if="isImage(asset)" class="image-select">
                <input v-model="selectedImageKeys" type="checkbox" :value="imageKey(message.id, asset.id)" :disabled="loading || mutationBusy"
                  :data-testid="`select-chat-image-${message.id}-${asset.id}`" />选择此图片
              </label>
              <button
                class="media-preview-button"
                type="button"
                :data-testid="`open-chat-image-${asset.id}`"
                :aria-label="`放大查看${message.text || message.message_type}`"
                @click="openImagePreview(message, asset)"
              >
                <img :src="scopedAssetUrl(asset.url)" :alt="message.text || message.message_type" loading="lazy" />
              </button>
              <button
                class="media-delete-button"
                type="button"
                :disabled="mutationBusy"
                :data-testid="`delete-chat-image-${asset.id}`"
                aria-label="删除这张聊天截图"
                @click="deleteImage(message, asset.id)"
              >{{ deletingAssetId === asset.id ? '删除中…' : '删除图片' }}</button>
            </div>
          </div>
        </article>
      </div>
      <nav v-if="selected" class="capture-pager" aria-label="聊天消息分页">
        <span>共 {{ total }} 项 · 每页 {{ pageSize }} 项（图片逐张分页）</span>
        <button type="button" data-testid="chat-page-first" :disabled="page <= 1 || loading || mutationBusy" @click="changePage(1)">首页</button>
        <button type="button" data-testid="chat-page-prev" :disabled="page <= 1 || loading || mutationBusy" @click="changePage(page - 1)">上一页</button>
        <template v-for="link in pageLinks" :key="link">
          <button v-if="typeof link === 'number'" type="button" :data-testid="`chat-page-${link}`" :aria-label="`第${link}页`" :aria-current="page === link ? 'page' : undefined" :class="{ active: page === link }" :disabled="loading || mutationBusy" @click="changePage(link)">{{ link }}</button>
          <span v-else class="pager-gap" aria-hidden="true">…</span>
        </template>
        <span aria-live="polite">{{ page }} / {{ totalPages }}</span>
        <button type="button" data-testid="chat-page-next" :disabled="page >= totalPages || loading || mutationBusy" @click="changePage(page + 1)">下一页</button>
        <button type="button" data-testid="chat-page-last" :disabled="page >= totalPages || loading || mutationBusy" @click="changePage(totalPages)">末页</button>
        <label class="page-jump">跳至 <input :value="pageJump" @input="pageJump = ($event.target as HTMLInputElement).value" data-testid="chat-page-jump-input" type="number" min="1" :max="totalPages" aria-label="跳转页码" :disabled="loading || mutationBusy" @keydown.enter.prevent="jumpToPage" /> 页</label>
        <button type="button" data-testid="chat-page-jump" :disabled="!validPageJump || loading || mutationBusy" @click="jumpToPage">跳转</button>
      </nav>
    </section>
  </div>

  </div>
  <div
    v-if="previewImage"
    ref="previewEl" tabindex="-1"
    @keydown="previewKeydown"
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
    <button class="image-preview-nav previous" data-testid="chat-image-prev" aria-label="上一张图片" :disabled="previewLoading || previewBoundary === 'previous'" @click="navigateImage('previous')">‹</button>
    <figure class="image-preview-figure" data-testid="chat-image-swipe"
      @pointerdown="startSwipe" @pointerup="endSwipe" @pointercancel="swipeStart = null">
      <img class="image-preview-image" :src="previewImage.src" :alt="previewImage.alt" draggable="false" @dragstart.prevent data-testid="chat-image-preview-image" />
      <figcaption class="image-preview-caption" aria-live="polite">
        <span v-if="previewLoading">加载图片中…</span>
        <span v-else-if="previewError">{{ previewError }}</span>
        <span v-else-if="previewImage.ordinal">第 {{ previewImage.ordinal }} / {{ previewImage.total }} 张 · 当前会话，最新在前</span>
        <span v-else>当前会话，最新在前 · 左右滑动或方向键切换</span>
      </figcaption>
    </figure>
    <button class="image-preview-nav next" data-testid="chat-image-next" aria-label="下一张图片" :disabled="previewLoading || previewBoundary === 'next'" @click="navigateImage('next')">›</button>
  </div>
</template>

<style scoped>
.pending-diagnostic { width: 100%; padding: 8px; background: #f6f7fb; border-radius: 6px; font-size: 12px; overflow-wrap: anywhere; }
.pending-diagnostic p { margin: 5px 0; }
.pending-diagnostic button { margin: 3px 6px 3px 0; }
.merge-panel { padding: 14px; margin: 12px 0; border: 1px solid #cbd5e1; border-radius: 8px; background: #f8fafc; }
.merge-panel form { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.merge-panel input { min-width: 0; flex: 1; padding: 6px; }
.merge-target { justify-content: flex-start; display: block; text-align: left; width: 100%; margin: 6px 0; padding: 8px; overflow-wrap: anywhere; }
.image-selection-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 10px 0; color: #657083; font-size: 12px; }
.image-selection-toolbar button { padding: 6px 10px; border: 1px solid #dfe4ea; border-radius: 6px; background: white; cursor: pointer; }
.image-select { display: flex; gap: 6px; align-items: center; font-size: 12px; cursor: pointer; }
.delete-notice { color: #23704b; font-size: 13px; }
.image-preview-figure { margin: 0; min-width: 0; touch-action: pan-y pinch-zoom; user-select: none; }
.image-preview-nav { position: fixed; top: 50%; z-index: 1; width: 42px; height: 52px; border: 0; border-radius: 8px; background: rgba(255,255,255,.9); font-size: 36px; cursor: pointer; transform: translateY(-50%); }
.image-preview-nav.previous { left: 10px; } .image-preview-nav.next { right: 10px; }
.image-preview-caption { position: fixed; bottom: 12px; left: 55px; right: 55px; text-align: center; color: white; font-size: 13px; pointer-events: none; }

.platform-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.platform-tabs button { padding: 9px 24px; border: 1px solid #d5dbe5; border-radius: 8px; background: white; cursor: pointer; }
.platform-tabs button.active { color: #fff; background: #2563eb; border-color: #2563eb; }
.platform-tabs button:disabled { opacity: .5; cursor: not-allowed; }

.capture-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }
.capture-stats .stat { display: flex; align-items: baseline; gap: 8px; padding: 10px 14px; }
.capture-stats .num { font-size: 22px; }
.capture-stats .label { margin: 0; }
.capture-layout { display: grid; grid-template-columns: 210px minmax(0, 1fr); align-items: start; gap: 12px; }
.conversation-panel, .timeline-panel { min-width: 0; padding: 14px; margin-bottom: 0; }
.conversation-panel { position: sticky; top: 12px; }
.conversation-list { max-height: calc(100vh - 170px); overflow-y: auto; }
.conversation-bulk-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.conversation-bulk-actions .timeline-summary { width: 100%; }
.conversation-bulk-actions button { font-size: 12px; padding: 5px 7px; }
.conversation-row { display: flex; align-items: center; gap: 4px; }
.conversation-checkbox { flex: none; width: 16px; height: 16px; margin: 0 2px; cursor: pointer; }
.conversation-row .conversation { min-width: 0; flex: 1; }
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
.capture-action { display: inline-flex; align-items: center; justify-content: center; gap: 4px; padding: 7px 11px; border: 1px solid #cbd1ff; border-radius: 6px; background: #f2f4ff; color: #3742fa; font: inherit; font-size: 13px; line-height: 1.4; cursor: pointer; transition: background .15s, border-color .15s; }
.capture-action:hover:not(:disabled) { background: #e5e9ff; border-color: #8792ff; }
.capture-action:focus-visible, .capture-pager button:focus-visible { outline: 2px solid #3742fa; outline-offset: 2px; }
.capture-source-action { width: 100%; justify-content: flex-start; padding: 5px 8px; font-size: 12px; overflow-wrap: anywhere; }
.capture-pager button.active { background: #3742fa; border-color: #3742fa; color: #fff; }
.page-jump { display: inline-flex; align-items: center; gap: 5px; }
.page-jump input { width: 64px; min-width: 0; padding: 5px 7px; border: 1px solid #dfe4ea; border-radius: 6px; }
.pager-gap { padding: 0 2px; }
.delete-button { padding: 7px 10px; border: 1px solid #ff6b81; border-radius: 6px; background: #fff; color: #c0392b; cursor: pointer; }
.delete-button:hover:not(:disabled) { background: #fff0f2; }
button:disabled { cursor: not-allowed; opacity: .5; }
.timeline { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); align-items: start; gap: 10px; }
.message { min-width: 0; padding: 10px; border: 1px solid #e9ecf2; border-radius: 8px; background: #f8f9fc; }
.message:not(.has-media) { grid-column: 1 / -1; }
.message.outgoing { background: #f1f3ff; }
.message.system { background: #fffcf5; }
.message-head { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; color: #747d8c; font-size: 12px; overflow-wrap: anywhere; }
.message-head > span:first-of-type { width: 100%; color: #2f3542; }
.message-head time { font-size: 11px; }
.badge { padding: 1px 5px; border-radius: 4px; background: rgba(55, 66, 250, .08); color: #3742fa; }
.message-text { margin: 6px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.5; }
.media-grid { display: grid; gap: 8px; margin-top: 8px; }
.media-item { display: grid; min-width: 0; gap: 5px; }
.media-preview-button { width: 100%; padding: 0; border: 0; border-radius: 6px; background: #edf0f5; cursor: zoom-in; }
.media-grid img { display: block; width: 100%; height: auto; aspect-ratio: 9 / 16; max-height: 360px; object-fit: contain; border-radius: 6px; }
.media-delete-button { justify-self: end; padding: 3px 7px; border: 1px solid #ff6b81; border-radius: 5px; background: #fff; color: #c0392b; cursor: pointer; font-size: 12px; }
.capture-pager { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 14px; color: #747d8c; font-size: 13px; }
.capture-pager > span:first-child { margin-right: auto; }
.capture-pager button, .error button { padding: 5px 10px; border: 1px solid #dfe4ea; border-radius: 6px; background: #fff; cursor: pointer; }
.image-preview-overlay { position: fixed; inset: 0; z-index: 1000; display: flex; align-items: center; justify-content: center; padding: 32px; background: rgba(15, 18, 28, .82); }
.image-preview-image { display: block; max-width: 92vw; max-height: 82vh; object-fit: contain; border-radius: 8px; box-shadow: 0 16px 48px rgba(0, 0, 0, .35); }
.image-preview-close { position: fixed; top: 18px; right: 22px; width: 42px; height: 42px; border: 0; border-radius: 50%; background: rgba(255, 255, 255, .92); color: #2f3542; font-size: 30px; line-height: 1; cursor: pointer; }
.error { padding: 10px 14px; margin-bottom: 16px; border-radius: 6px; background: #fff0f0; color: #c0392b; }
@media (max-width: 1200px) { .timeline { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
@media (max-width: 1000px) {
  .timeline { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .capture-layout { grid-template-columns: 1fr; }
  .conversation-panel { position: static; }
  .conversation-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 180px), 1fr)); max-height: 150px; }
}
@media (max-width: 600px) {
  .timeline { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .capture-pager { gap: 5px; }
  .capture-pager > span:first-child { width: 100%; }
  .capture-stats .stat { flex-wrap: wrap; gap: 2px 6px; padding: 8px; }
  .capture-stats .label { font-size: 11px; }
  .conversation-panel, .timeline-panel { padding: 10px; }
  .image-preview-overlay { padding: 16px; }
}
</style>
