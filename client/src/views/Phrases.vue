<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, type UserPhraseRow } from '../api';
import { phrasePresetGroups } from '../data/phrasePresets';
import './content-library.css';

const phrases = ref<UserPhraseRow[]>([]);
const loading = ref(false);
const loaded = ref(false);
const busy = ref(false);
const msg = ref('');
const err = ref('');
const q = ref('');
const tab = ref<'mine' | 'presets'>('mine');
const category = ref('全部');
const newContent = ref('');
const editingId = ref<number | null>(null);
const editingContent = ref('');
const presets = phrasePresetGroups.flatMap(group => group.phrases.map(content => ({ content, category: group.category })));
const saved = computed(() => new Set(phrases.value.map(p => p.content)));
const filteredPhrases = computed(() => phrases.value.filter(p => p.content.includes(q.value.trim())));
const filteredPresets = computed(() => presets.filter(p => (category.value === '全部' || p.category === category.value) && p.content.includes(q.value.trim())));

async function load() {
  loading.value = true;
  err.value = '';
  try {
    phrases.value = (await api.userPhrases()).phrases;
    loaded.value = true;
  } catch (e) { err.value = `加载失败：${(e as Error).message}`; }
  finally { loading.value = false; }
}
async function add(content: string, fromPreset = false) {
  content = content.trim();
  if (busy.value) return;
  if (!content || content.length > 500) { err.value = '请输入 1～500 字的常用语'; return; }
  if (saved.value.has(content)) { msg.value = '这条已在你的常用语中'; return; }
  busy.value = true; msg.value = ''; err.value = '';
  try {
    const row = await api.addUserPhrase(content);
    phrases.value.unshift(row);
    msg.value = '已加入我的常用语，手机端下次成功同步后可使用。';
    if (!fromPreset) newContent.value = '';
  } catch (e) { err.value = `添加失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
function startEdit(p: UserPhraseRow) { editingId.value = p.id; editingContent.value = p.content; err.value = ''; }
async function saveEdit(p: UserPhraseRow) {
  const content = editingContent.value.trim();
  if (busy.value) return;
  if (!content || content.length > 500) { err.value = '请输入 1～500 字的常用语'; return; }
  busy.value = true; err.value = ''; msg.value = '';
  try { await api.updateUserPhrase(p.id, content); p.content = content; editingId.value = null; msg.value = '常用语已更新'; }
  catch (e) { err.value = `保存失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
async function remove(p: UserPhraseRow) {
  if (busy.value || !confirm(`删除常用语「${p.content}」？手机下次成功同步后也会移除。`)) return;
  busy.value = true; err.value = ''; msg.value = '';
  try { await api.deleteUserPhrase(p.id); phrases.value = phrases.value.filter(x => x.id !== p.id); msg.value = '常用语已删除'; }
  catch (e) { err.value = `删除失败：${(e as Error).message}`; }
  finally { busy.value = false; }
}
onMounted(load);
</script>

<template>
  <div class="content-library phrase-page">
    <header class="library-intro">
      <div class="intro-mark" aria-hidden="true">“</div>
      <div><span class="eyebrow">QUICK PHRASES</span><h2>常说的话，一键就好</h2><p>管理你的快捷短句，也可以从预置库挑选。只有加入“我的常用语”后才会同步到手机。</p></div>
      <div class="intro-number"><strong>{{ phrases.length }}</strong><span>我的常用语</span></div>
    </header>

    <section class="library-panel compose-panel">
      <div class="section-heading"><div><h3>添加自己的常用语</h3><p>手机本地新增的短句也可同步到这里，这里不是输入法的完整词库。</p></div></div>
      <form class="library-row" @submit.prevent="add(newContent)">
        <input v-model="newContent" class="library-input" aria-label="新的常用语" maxlength="500" placeholder="写一句经常用的话，例如：收到，我确认后回复你。" />
        <button class="library-button primary" :disabled="busy || loading || !loaded || !newContent.trim()" type="submit">＋ 添加常用语</button>
      </form>
    </section>
    <p v-if="msg" class="library-notice success" role="status">{{ msg }}</p>
    <div v-if="err" class="library-notice error" role="alert">{{ err }} <button v-if="!loaded" class="text-button" :disabled="loading" @click="load">重试</button></div>

    <section class="library-panel">
      <div class="library-toolbar">
        <div class="library-tabs" role="tablist" aria-label="常用语来源">
          <button :class="{ active: tab === 'mine' }" role="tab" :aria-selected="tab === 'mine'" @click="tab = 'mine'">我的常用语 <span>{{ phrases.length }}</span></button>
          <button data-testid="preset-tab" :class="{ active: tab === 'presets' }" role="tab" :aria-selected="tab === 'presets'" @click="tab = 'presets'">预置常用语 <span>{{ presets.length }}</span></button>
        </div>
        <input v-model="q" class="library-input library-search" type="search" aria-label="搜索常用语" placeholder="搜索短句内容…" />
      </div>
      <template v-if="tab === 'presets'">
        <div class="library-chips" aria-label="预置分类">
          <button v-for="name in ['全部', ...phrasePresetGroups.map(g => g.category)]" :key="name" :class="{ active: category === name }" :aria-pressed="category === name" @click="category = name">{{ name }}</button>
        </div>
        <div class="preset-grid">
          <article v-for="p in filteredPresets" :key="p.content" class="preset-card">
            <span class="library-badge">{{ p.category }}</span><p>{{ p.content }}</p>
            <button data-testid="add-preset" class="library-button" :class="{ saved: saved.has(p.content) }" :disabled="busy || loading || !loaded || saved.has(p.content)" @click="add(p.content, true)">{{ saved.has(p.content) ? '✓ 已加入' : '＋ 加入我的常用语' }}</button>
          </article>
        </div>
        <div v-if="!filteredPresets.length" class="library-empty"><strong>没有匹配的短句</strong><p>试试其他关键词或分类，也可以在上方自行添加。</p></div>
      </template>
      <template v-else>
        <div v-if="loading" class="library-empty" role="status">正在加载常用语…</div>
        <div v-else-if="!filteredPhrases.length" class="library-empty"><span class="empty-mark" aria-hidden="true">“</span><strong>{{ q ? '没有找到匹配的常用语' : '把常说的话收藏在这里' }}</strong><p>{{ q ? '试试缩短关键词。' : '在上方添加，或到预置库挑几句，不必逐条手动输入。' }}</p><button v-if="!q" class="library-button" @click="tab = 'presets'">浏览预置常用语 →</button></div>
        <ul v-else class="phrase-list">
          <li v-for="(p, index) in filteredPhrases" :key="p.id" class="phrase-item">
            <span class="phrase-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="phrase-body">
              <input v-if="editingId === p.id" v-model="editingContent" data-testid="edit-phrase-input" class="library-input" aria-label="编辑常用语" maxlength="500" @keyup.enter="saveEdit(p)" @keyup.esc="editingId = null" />
              <p v-else>{{ p.content }}</p><small>使用 {{ p.useCount }} 次</small>
            </div>
            <div class="library-actions">
              <template v-if="editingId === p.id"><button data-testid="save-phrase" class="library-button primary small" :disabled="busy" @click="saveEdit(p)">保存</button><button class="library-button small" :disabled="busy" @click="editingId = null">取消</button></template>
              <button v-else :data-testid="`edit-phrase-${p.id}`" class="library-button small" :disabled="busy" @click="startEdit(p)">编辑</button>
              <button class="text-button danger" :disabled="busy" @click="remove(p)">删除</button>
            </div>
          </li>
        </ul>
      </template>
    </section>
  </div>
</template>
