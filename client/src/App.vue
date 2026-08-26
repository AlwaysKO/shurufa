<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api, currentUserId, deviceLabel, setCurrentUserId, type DeviceRow } from './api';

type NavGroup = { key: string; label: string; icon: string; items: Array<{ path: string; label: string }> };
const route = useRoute();
const selectedUser = ref<DeviceRow | null>(null);
const usersReady = ref(false);
const initializationError = ref('');
const directoryOpen = ref(false);
const directoryUsers = ref<DeviceRow[]>([]);
const directoryTotal = ref(0);
const directoryPage = ref(1);
const directoryQuery = ref('');
const directoryLoading = ref(false);
const directoryError = ref('');
const editingUser = ref<DeviceRow | null>(null);
const editName = ref('');
const editTags = ref('');
const editSaving = ref(false);
const editError = ref('');
const pageSize = 12;
let directoryRequestId = 0;
const openGroups = ref(new Set<string>(['overview']));
const navGroups: NavGroup[] = [
  { key: 'overview', label: '概览', icon: '▦', items: [
    { path: '/', label: '输入总览' }, { path: '/timeline', label: '时间线' }, { path: '/report', label: '输入报告' },
  ] },
  { key: 'analysis', label: '输入分析', icon: '⌁', items: [
    { path: '/apps', label: 'APP 分布' }, { path: '/phrases', label: '高频词句' },
    { path: '/wordcloud', label: '词云' }, { path: '/completion', label: '智能补全' },
  ] },
  { key: 'records', label: '内容记录', icon: '▤', items: [
    { path: '/activity', label: '行为明细' }, { path: '/clipboard', label: '剪贴板' },
    { path: '/clipboard-history', label: '剪贴板历史' }, { path: '/chat-capture', label: '聊天采集' },
  ] },
  { key: 'assets', label: '个人资产', icon: '◇', items: [
    { path: '/user-phrases', label: '常用语' }, { path: '/stickers', label: '表情包' },
    { path: '/relationships', label: '关系记忆' },
  ] },
  { key: 'manage', label: '设备与数据', icon: '⚙', items: [
    { path: '/locations', label: '位置轨迹' }, { path: '/data', label: '数据管理' },
  ] },
];
const pageCount = computed(() => Math.max(1, Math.ceil(directoryTotal.value / pageSize)));

watch(() => route.path, (path) => {
  const group = navGroups.find((g) => g.items.some((item) => item.path === path));
  if (group && !openGroups.value.has(group.key)) openGroups.value = new Set([...openGroups.value, group.key]);
}, { immediate: true });

async function initializeUsers() {
  usersReady.value = false;
  initializationError.value = '';
  try {
    if (currentUserId.value) selectedUser.value = (await api.users({ id: currentUserId.value, page_size: 1 })).users[0] ?? null;
    if (!selectedUser.value) {
      selectedUser.value = (await api.users({ page_size: 1 })).users[0] ?? null;
      setCurrentUserId(selectedUser.value?.id ?? '');
    }
  } catch (error) {
    selectedUser.value = null;
    setCurrentUserId('');
    initializationError.value = error instanceof Error ? error.message : '用户目录加载失败';
  } finally { usersReady.value = true; }
}
onMounted(() => void initializeUsers());

function toggleGroup(key: string) {
  const next = new Set(openGroups.value);
  next.has(key) ? next.delete(key) : next.add(key);
  openGroups.value = next;
}
async function loadDirectory(page = 1) {
  const requestId = ++directoryRequestId;
  directoryLoading.value = true; directoryError.value = '';
  try {
    const result = await api.users({ q: directoryQuery.value.trim() || undefined, page, page_size: pageSize });
    if (requestId !== directoryRequestId) return;
    directoryUsers.value = result.users; directoryTotal.value = result.total; directoryPage.value = result.page;
  } catch (error) {
    if (requestId === directoryRequestId) directoryError.value = error instanceof Error ? error.message : '用户目录加载失败';
  } finally {
    if (requestId === directoryRequestId) directoryLoading.value = false;
  }
}
function openDirectory() {
  directoryOpen.value = true; directoryQuery.value = ''; editingUser.value = null; editError.value = ''; void loadDirectory(1);
}
function closeDirectory() { directoryOpen.value = false; directoryRequestId += 1; directoryLoading.value = false; }
function chooseUser(user: DeviceRow) { selectedUser.value = user; setCurrentUserId(user.id); closeDirectory(); }
function editUser(user: DeviceRow) {
  editingUser.value = user; editName.value = user.dashboard_name ?? ''; editTags.value = user.tags ?? ''; editError.value = '';
}
async function saveUser() {
  if (!editingUser.value || editSaving.value) return;
  editSaving.value = true; editError.value = '';
  try {
    const result = await api.updateUser(editingUser.value.id, { dashboard_name: editName.value, tags: editTags.value });
    directoryUsers.value = directoryUsers.value.map((user) => user.id === result.user.id ? result.user : user);
    if (selectedUser.value?.id === result.user.id) selectedUser.value = result.user;
    editingUser.value = null;
  } catch (error) { editError.value = error instanceof Error ? error.message : '保存失败'; }
  finally { editSaving.value = false; }
}
function subtitle(user: DeviceRow) {
  return user.tags?.trim() || [user.brand, user.model].filter(Boolean).join(' ') || user.id;
}
function seenAt(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }); }
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">⌨️ 我的输入法</div>
      <button class="current-user" type="button" @click="openDirectory">
        <span class="user-avatar">📱</span>
        <span class="user-summary">
          <small>当前用户</small>
          <strong>{{ selectedUser ? deviceLabel(selectedUser) : '选择用户' }}</strong>
          <span v-if="selectedUser">{{ subtitle(selectedUser) }}</span>
        </span>
        <b>切换</b>
      </button>
      <nav class="sidebar-nav">
        <section v-for="group in navGroups" :key="group.key" class="nav-group">
          <button type="button" class="nav-group-title" :class="{ active: group.items.some((i) => i.path === route.path) }" @click="toggleGroup(group.key)">
            <span class="group-icon">{{ group.icon }}</span>{{ group.label }}
            <span class="group-arrow" :class="{ open: openGroups.has(group.key) }">›</span>
          </button>
          <div v-show="openGroups.has(group.key)" class="nav-children">
            <RouterLink v-for="item in group.items" :key="item.path" :to="item.path" class="nav-item" :class="{ active: route.path === item.path }">
              {{ item.label }}
            </RouterLink>
          </div>
        </section>
      </nav>
    </aside>
    <main class="content">
      <h1 class="page-title">{{ route.meta.title }}</h1>
      <RouterView v-if="usersReady && currentUserId && selectedUser" :key="`${route.fullPath}:${currentUserId}`" />
      <div v-else-if="usersReady && initializationError" class="card empty load-error">
        <p>用户目录加载失败，请检查后台服务。</p><button type="button" @click="initializeUsers">重试</button>
      </div>
      <div v-else-if="usersReady" class="card empty">暂无已注册手机，请先在手机端启动输入法。</div>
    </main>

    <Teleport to="body">
      <div v-if="directoryOpen" class="directory-mask" @click.self="closeDirectory">
        <section class="directory-dialog" role="dialog" aria-modal="true" aria-label="选择用户">
          <header class="directory-header">
            <div><h2>选择用户</h2><p>共 {{ directoryTotal }} 台设备，按最近活跃排序</p></div>
            <button type="button" class="close-button" @click="closeDirectory">×</button>
          </header>
          <form class="directory-search" @submit.prevent="loadDirectory(1)">
            <input v-model="directoryQuery" placeholder="搜索名称、品牌、型号或设备 ID" autofocus />
            <button type="submit" :disabled="directoryLoading">搜索</button>
          </form>
          <form v-if="editingUser" class="edit-panel" @submit.prevent="saveUser">
            <div class="edit-panel-title"><strong>编辑用户标识</strong><span>为同型号手机设置唯一名称和标签</span></div>
            <input v-model="editName" maxlength="100" placeholder="用户名称，如：上海客服 025" />
            <input v-model="editTags" maxlength="500" placeholder="标签，如：上海、客服、VIP" />
            <span v-if="editError" class="edit-error">{{ editError }}</span>
            <div><button type="button" @click="editingUser = null">取消</button><button type="submit" :disabled="editSaving">{{ editSaving ? '保存中…' : '保存' }}</button></div>
          </form>
          <div v-if="directoryError" class="directory-state error">{{ directoryError }}</div>
          <div v-else-if="directoryLoading" class="directory-state">正在加载用户目录…</div>
          <div v-else-if="!directoryUsers.length" class="directory-state">没有找到匹配的用户</div>
          <div v-else class="user-list">
            <div v-for="user in directoryUsers" :key="user.id" class="user-row" :class="{ selected: user.id === currentUserId }">
              <button type="button" class="user-select" @click="chooseUser(user)">
                <span class="user-row-avatar">📱</span>
                <span class="user-row-main"><strong>{{ deviceLabel(user) }}</strong><span>{{ subtitle(user) }}</span><code>{{ user.id }}</code></span>
                <span class="user-row-meta"><span>{{ seenAt(user.last_seen_at) }}</span><b v-if="user.id === currentUserId">当前</b></span>
              </button>
              <button type="button" class="edit-user" @click="editUser(user)">编辑</button>
            </div>
          </div>
          <footer class="directory-footer">
            <span>第 {{ directoryPage }} / {{ pageCount }} 页</span>
            <div>
              <button type="button" :disabled="directoryPage <= 1 || directoryLoading" @click="loadDirectory(directoryPage - 1)">上一页</button>
              <button type="button" :disabled="directoryPage >= pageCount || directoryLoading" @click="loadDirectory(directoryPage + 1)">下一页</button>
            </div>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif; background: #f5f6fa; color: #2f3542; }
.layout { display: flex; min-height: 100vh; }
.sidebar { position: sticky; top: 0; width: 224px; height: 100vh; overflow-y: auto; background: #293342; color: #fff; padding: 20px 0; flex-shrink: 0; }
.logo { font-size: 16px; font-weight: 600; padding: 0 20px 20px; border-bottom: 1px solid rgba(255,255,255,.1); }
.content { flex: 1; padding: 24px 32px; overflow-x: hidden; }
.page-title { font-size: 20px; margin-bottom: 20px; }
.card { background: #fff; border-radius: 10px; padding: 20px; margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.card h3 { font-size: 14px; color: #747d8c; margin-bottom: 12px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; }
.stat { background: #fff; border-radius: 10px; padding: 16px 20px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.stat .num { font-size: 26px; font-weight: 700; color: #3742fa; }
.stat .label { font-size: 12px; color: #747d8c; margin-top: 4px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #f1f2f6; }
th { color: #747d8c; font-weight: 500; background: #fafbfc; }
.chart { width: 100%; height: 320px; }
.filters { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.filters button { padding: 6px 14px; border-radius: 16px; border: 1px solid #dfe4ea; background: #fff; cursor: pointer; font-size: 13px; color: #57606f; }
.filters button.active { background: #3742fa; color: #fff; border-color: #3742fa; }
.pct-bar { display: inline-block; height: 8px; border-radius: 4px; background: #3742fa; vertical-align: middle; margin-right: 8px; }
.empty { color: #a4b0be; text-align: center; padding: 40px 0; font-size: 14px; }
.load-error p { margin-bottom:12px; color:#d35454; }.load-error button { padding:8px 18px; border:0; border-radius:7px; background:#4451e8; color:#fff; cursor:pointer; }

button, input { font: inherit; }
.current-user { display:flex; align-items:center; gap:9px; width:calc(100% - 20px); margin:12px 10px 8px; padding:11px; border:1px solid rgba(255,255,255,.12); border-radius:9px; background:rgba(255,255,255,.06); color:#fff; text-align:left; cursor:pointer; }
.current-user:hover { background:rgba(255,255,255,.1); }
.user-avatar,.user-row-avatar { display:grid; place-items:center; flex-shrink:0; border-radius:9px; background:rgba(68,81,232,.25); }
.user-avatar { width:32px; height:32px; }
.user-summary { min-width:0; flex:1; display:flex; flex-direction:column; gap:2px; }
.user-summary strong,.user-summary span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.user-summary strong { font-size:13px; }.user-summary small,.user-summary span { color:rgba(255,255,255,.5); font-size:10px; }.current-user>b { color:#9ba7ff; font-size:11px; }
.sidebar-nav { padding:6px 8px 20px; }.nav-group { margin-bottom:3px; }
.nav-group-title { display:flex; align-items:center; width:100%; padding:10px; border:0; border-radius:7px; background:transparent; color:rgba(255,255,255,.72); cursor:pointer; font-size:13px; text-align:left; }
.nav-group-title:hover,.nav-group-title.active { color:#fff; background:rgba(255,255,255,.06); }.group-icon { width:24px; color:#9ba7ff; font-size:15px; }.group-arrow { margin-left:auto; font-size:18px; transition:transform .18s; }.group-arrow.open { transform:rotate(90deg); }
.nav-children { padding:2px 0 5px 24px; }.nav-item { display:block; padding:8px 12px; border-radius:6px; color:rgba(255,255,255,.58); text-decoration:none; font-size:13px; }.nav-item:hover { color:#fff; background:rgba(255,255,255,.06); }.nav-item.active { background:#4451e8; color:#fff; }
.directory-mask { position:fixed; z-index:1000; inset:0; display:grid; place-items:center; padding:24px; background:rgba(20,28,40,.58); backdrop-filter:blur(2px); }
.directory-dialog { display:flex; flex-direction:column; width:min(720px,100%); max-height:min(760px,calc(100vh - 48px)); overflow:hidden; border-radius:14px; background:#fff; box-shadow:0 24px 80px rgba(0,0,0,.25); }
.directory-header { display:flex; justify-content:space-between; padding:22px 24px 14px; }.directory-header h2 { font-size:20px; }.directory-header p { margin-top:5px; color:#8a94a3; font-size:12px; }.close-button { width:32px; height:32px; border:0; border-radius:50%; background:#f1f3f7; color:#677080; cursor:pointer; font-size:22px; }
.directory-search { display:flex; gap:8px; padding:0 24px 16px; }.directory-search input { flex:1; min-width:0; padding:10px 12px; border:1px solid #dfe4ea; border-radius:8px; outline:none; }.directory-search input:focus { border-color:#5663ea; box-shadow:0 0 0 3px rgba(86,99,234,.12); }.directory-search button,.directory-footer button,.edit-panel button { padding:9px 16px; border:0; border-radius:7px; background:#4451e8; color:#fff; cursor:pointer; }.directory-search button:disabled { opacity:.55; cursor:not-allowed; }
.edit-panel { display:grid; grid-template-columns:1fr 1fr auto; gap:8px; margin:0 24px 16px; padding:12px; border:1px solid #dfe3ff; border-radius:9px; background:#f7f8ff; }.edit-panel-title { grid-column:1/-1; display:flex; align-items:baseline; gap:10px; }.edit-panel-title strong { font-size:13px; }.edit-panel-title span { color:#8a94a3; font-size:11px; }.edit-panel input { min-width:0; padding:8px 10px; border:1px solid #dfe4ea; border-radius:7px; }.edit-panel>div:last-child { display:flex; gap:6px; }.edit-panel>div:last-child button:first-child { background:#e5e8ef; color:#596273; }.edit-panel button:disabled { opacity:.55; }.edit-error { grid-column:1/-1; color:#e74c3c; font-size:11px; }
.directory-state { padding:60px 24px; text-align:center; color:#8a94a3; }.directory-state.error { color:#e74c3c; }.user-list { overflow-y:auto; padding:0 14px; border-block:1px solid #f0f2f5; }.user-row { display:flex; align-items:center; width:100%; border-bottom:1px solid #f3f4f6; background:#fff; color:#2f3542; }.user-row:hover { background:#f7f8ff; }.user-row.selected { background:#f0f2ff; }.user-select { display:flex; align-items:center; gap:12px; min-width:0; flex:1; padding:13px 10px; border:0; background:transparent; color:inherit; text-align:left; cursor:pointer; }.edit-user { margin-right:10px; padding:6px 10px; border:1px solid #dfe3eb; border-radius:6px; background:#fff; color:#657083; cursor:pointer; font-size:11px; }.edit-user:hover { border-color:#4451e8; color:#4451e8; }.user-row-avatar { width:38px; height:38px; background:#eef0ff; }
.user-row-main { min-width:0; flex:1; display:flex; flex-direction:column; gap:3px; }.user-row-main strong { font-size:14px; }.user-row-main span { color:#697386; font-size:12px; }.user-row-main code { overflow:hidden; color:#a0a7b2; font-size:10px; text-overflow:ellipsis; }.user-row-meta { display:flex; flex-direction:column; align-items:flex-end; gap:6px; color:#a0a7b2; font-size:11px; }.user-row-meta b { padding:2px 7px; border-radius:10px; background:#4451e8; color:#fff; font-size:10px; }
.directory-footer { display:flex; align-items:center; justify-content:space-between; padding:14px 24px; color:#7f8896; font-size:12px; }.directory-footer div { display:flex; gap:8px; }.directory-footer button { padding:7px 13px; }.directory-footer button:disabled { background:#dfe3eb; cursor:not-allowed; }
@media(max-width:760px){.sidebar{width:190px}.content{padding:18px}.directory-mask{padding:10px}.user-row-meta{display:none}.edit-panel{grid-template-columns:1fr}.edit-panel-title,.edit-error{grid-column:1}}
</style>
