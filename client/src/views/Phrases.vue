<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api, type UserPhraseRow } from '../api';

const phrases = ref<UserPhraseRow[]>([]);
const total = ref(0);
const q = ref('');
const loading = ref(false);
const msg = ref('');
const err = ref('');

// 新增表单
const adding = ref(false);
const newContent = ref('');

async function load() {
  loading.value = true;
  try {
    const data = await api.userPhrases(q.value);
    phrases.value = data.phrases;
    total.value = data.total;
  } catch (e) {
    err.value = `加载失败：${(e as Error).message}`;
  } finally {
    loading.value = false;
  }
}

async function doAdd() {
  const content = newContent.value.trim();
  if (!content) {
    err.value = '请填写常用语内容';
    return;
  }
  adding.value = true;
  msg.value = '';
  err.value = '';
  try {
    await api.addUserPhrase(content);
    msg.value = '已添加，输入法端下次打开「常用语」面板时自动同步';
    newContent.value = '';
    await load();
  } catch (e) {
    err.value = `添加失败：${(e as Error).message}`;
  } finally {
    adding.value = false;
  }
}

const editingId = ref<number | null>(null);
const editingContent = ref('');

function startEdit(p: UserPhraseRow) {
  editingId.value = p.id;
  editingContent.value = p.content;
}

async function saveEdit(p: UserPhraseRow) {
  const content = editingContent.value.trim();
  if (!content) {
    err.value = '内容不能为空';
    return;
  }
  try {
    await api.updateUserPhrase(p.id, content);
    p.content = content;
  } catch (e) {
    err.value = `保存失败：${(e as Error).message}`;
  }
  editingId.value = null;
}

async function doDelete(p: UserPhraseRow) {
  if (!confirm(`删除常用语「${p.content}」？输入法端下次同步后也会移除。`)) return;
  try {
    await api.deleteUserPhrase(p.id);
    phrases.value = phrases.value.filter((x) => x.id !== p.id);
    total.value -= 1;
  } catch (e) {
    err.value = `删除失败：${(e as Error).message}`;
  }
}

onMounted(load);
</script>

<template>
  <div class="card">
    <h3>添加常用语</h3>
    <p class="desc">常用语会同步到输入法的「常用语」面板（键盘菜单 → 常用语），点击即可一键插入。手机本地新增的常用语也会自动上报到这里。</p>
    <div class="form-row">
      <input v-model="newContent" class="input" placeholder="输入常用语内容，如：麻烦发顺丰到付，谢谢。" @keyup.enter="doAdd" />
      <button class="primary" :disabled="adding" @click="doAdd">{{ adding ? '添加中…' : '添加' }}</button>
    </div>
    <p v-if="msg" class="msg ok">{{ msg }}</p>
    <p v-if="err" class="msg err">{{ err }}</p>
  </div>

  <div class="card">
    <h3>常用语库 <span class="count">{{ total }} 条</span></h3>
    <div class="form-row">
      <input v-model="q" class="input" placeholder="搜索内容…" @keyup.enter="load" />
      <button class="primary" :disabled="loading" @click="load">搜索</button>
    </div>

    <p v-if="loading" class="msg">加载中…</p>
    <p v-else-if="phrases.length === 0" class="msg">暂无常用语，添加一条吧</p>

    <ul v-else class="phrase-list">
      <li v-for="p in phrases" :key="p.id" class="phrase-item">
        <template v-if="editingId === p.id">
          <input v-model="editingContent" class="input" @keyup.enter="saveEdit(p)" />
        </template>
        <template v-else>
          <span class="phrase-content">{{ p.content }}</span>
        </template>
        <span class="phrase-stats">使用 {{ p.useCount }} 次</span>
        <div class="actions">
          <button class="ghost" @click="editingId === p.id ? saveEdit(p) : startEdit(p)">
            {{ editingId === p.id ? '保存' : '编辑' }}
          </button>
          <button class="ghost danger" @click="doDelete(p)">删除</button>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.count {
  font-weight: normal;
  color: var(--muted, #888);
  font-size: 0.85em;
}
.phrase-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.phrase-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--border, #ddd);
  border-radius: 8px;
}
.phrase-content {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.phrase-stats {
  flex-shrink: 0;
  font-size: 0.75em;
  color: var(--muted, #888);
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
</style>
