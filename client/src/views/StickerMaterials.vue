<script setup lang="ts">
import './content-library.css';
import { runMaterialBatch, type BatchRow, type BatchStatus } from '../api/stickerMaterialBatch';
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import {
  stickerMaterials,
  type Material,
  type MaterialQuery,
  type ImportAgent,
  type ImportJob,
} from '../api/stickerMaterials';
import { scopedAssetUrl } from '../api';
import { useConfirmation } from '../confirmation';
const emit = defineEmits<{ changed: [] }>();
const confirm = useConfirmation();
const batchRows = ref<BatchRow[]>([]), batchBusy = ref(false), batchError = ref('');
const batchInput = ref<HTMLInputElement | null>(null);
const batchCancelled = ref(false);
const batchLabels: Record<BatchStatus, string> = {
  pending: '等待处理', hashing: '计算原图指纹', matching: '比对后台', uploading: '上传原图',
  existing: '后台已有', imported: '新增成功', duplicate: '本批重复，已跳过', failed: '失败', cancelled: '已停止，待重试',
};
const batchFinished = computed(() => batchRows.value.filter(r => ['existing','imported','duplicate','failed','cancelled'].includes(r.status)).length);
const batchRetryable = computed(() => batchRows.value.some(r => ['failed','cancelled'].includes(r.status)));
async function runBatch() {
  if (!alive || batchBusy.value || keywordBusy.value) return;
  batchBusy.value = true;
  batchCancelled.value = false;
  try {
    await runMaterialBatch(batchRows.value, stickerMaterials, () => !alive || batchCancelled.value);
  } finally {
    if (alive) {
      batchBusy.value = false;
      emit('changed');
      await loadMaterials(1);
    }
  }
}
async function chooseBatch(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = '';
  if (batchBusy.value || keywordBusy.value || !files.length) return;
  batchError.value = '';
  if (files.length > 1000) { batchError.value = '每批最多选择 1000 张，请分批上传'; return; }
  batchRows.value = files.map(file => ({ file, status: 'pending' }));
  await runBatch();
}
function cancelBatch() { batchCancelled.value = true; }

const items = ref<Material[]>([]),
  warnings = ref<string[]>([]);
const filter = ref<MaterialQuery['state']>('all'),
  search = ref('');
const page = ref(1),
  total = ref(0),
  loading = ref(false),
  materialError = ref('');
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / 30)));
let query: MaterialQuery = { state: 'all', q: '', page: 1, page_size: 30 };
let selectedJobId = '';
let alive = true,
  materialEpoch = 0,
  jobEpoch = 0,
  contextEpoch = 0;
let pollTimer: ReturnType<typeof setTimeout> | undefined,
  clockTimer: ReturnType<typeof setInterval> | undefined;
const cardBusy = ref<Record<string, boolean>>({}),
  cardErrors = ref<Record<string, string>>({}),
  drafts = ref<Record<string, string>>({});
// 批量匹配缓存与关键词差量编辑互斥，避免同批结果显示编辑前的标签。
const keywordBusy = computed(() => Object.values(cardBusy.value).some(Boolean));
const failedImages = ref(new Set<string>());
const previewUrl = (m: Material) => {
  const url = scopedAssetUrl(m.url);
  return `${url}${url.includes('?') ? '&' : '?'}v=${m.sha256}`;
};
async function loadMaterials(nextPage = page.value) {
  const epoch = ++materialEpoch;
  loading.value = true;
  materialError.value = '';
  try {
    const result = await stickerMaterials.list({ ...query, page: nextPage });
    if (!alive || epoch !== materialEpoch) return;
    items.value = result.items;
    warnings.value = result.warnings;
    total.value = result.total;
    page.value = result.page;
    if (nextPage > 1 && !result.items.length && result.total > 0) {
      await loadMaterials(Math.max(1, Math.ceil(result.total / 30)));
    }
  } catch (e) {
    if (alive && epoch === materialEpoch) materialError.value = (e as Error).message;
  } finally {
    if (alive && epoch === materialEpoch) loading.value = false;
  }
}
function applyFilter() {
  query = { state: filter.value, q: search.value.trim(), page: 1, page_size: 30 };
  return loadMaterials(1);
}
async function changeKeywords(m: Material, add: string[], remove: string[]) {
  if (batchBusy.value || cardBusy.value[m.sha256] || (!add.length && !remove.length)) return;
  cardBusy.value[m.sha256] = true;
  cardErrors.value[m.sha256] = '';
  try {
    const { material } = await stickerMaterials.keywords(m.sha256, { add, remove });
    if (!alive) return;
    const index = items.value.findIndex((item) => item.sha256 === m.sha256);
    if (index >= 0) items.value[index] = material;
    drafts.value[m.sha256] = '';
    for (const row of batchRows.value) if (row.sha256 === m.sha256) row.material = material;
    emit('changed');
    await loadMaterials();
  } catch (e) {
    if (alive) cardErrors.value[m.sha256] = (e as Error).message;
  } finally {
    if (alive) cardBusy.value[m.sha256] = false;
  }
}
function addKeywords(m: Material) {
  return changeKeywords(
    m,
    [
      ...new Set(
        (drafts.value[m.sha256] || '')
          .split(/[,，\n]/)
          .map((s) => s.trim())
          .filter(Boolean),
      ),
    ],
    [],
  );
}
const agents = ref<ImportAgent[]>([]),
  selectedAgent = ref(''),
  jobs = ref<ImportJob[]>([]),
  currentJob = ref<ImportJob | null>(null);
const now = ref(Date.now()),
  controlError = ref(''),
  pollError = ref(''),
  actionBusy = ref(false);
const pairing = ref<{ code: string; expiresAt: string } | null>(null),
  copyMessage = ref('');
const pairingExpired = computed(
  () => !!pairing.value && now.value >= Date.parse(pairing.value.expiresAt),
);
const activeAgents = computed(() => agents.value.filter((a) => !a.revokedAt));
const agent = computed(() => agents.value.find((a) => a.id === selectedAgent.value));
const online = computed(
  () =>
    !!agent.value &&
    !agent.value.revokedAt &&
    !!agent.value.lastSeenAt &&
    now.value - Date.parse(agent.value.lastSeenAt) <= 90000,
);
const agentJobs = computed(() => jobs.value.filter((j) => j.agentId === selectedAgent.value));
const isActive = (job: ImportJob | null) => !!job && ['queued', 'running'].includes(job.status);
const hasActive = computed(
  () =>
    agentJobs.value.some(isActive) ||
    (currentJob.value?.agentId === selectedAgent.value && isActive(currentJob.value)),
);
const statuses: Record<string, string> = {
  queued: '等待本机助手',
  running: '导入进行中',
  completed: '导入完成',
  failed: '导入失败',
  cancelled: '已取消',
  expired: '已过期',
};
const statusText = (status: string) => statuses[status] || '未知状态';
const errorNames: Record<string, string> = {
  key_not_found: '未能解锁收藏表情，请打开微信表情面板后重试',
  snapshot_unstable: '微信数据正变化，请稍后重试',
  wechat_not_running: '微信未运行',
  download_failed: '原图下载失败',
  invalid_image: '图片校验不通过',
  collector_failed: '本机采集失败',
  unavailable: '线上原图缺失或不符，未重复上传',
  hash_mismatch: '文件哈希不符',
  upload_failed: '图片上传失败',
};
const errorText = (code: string) => errorNames[code] || '助手或服务处理失败，请检查助手运行状态';
const partialFailure = computed(
  () =>
    !!currentJob.value &&
    (currentJob.value.validationFailed > 0 ||
      currentJob.value.counts.failed > 0 ||
      Object.values(currentJob.value.sourceErrors).some((n) => n > 0)),
);
const completionSeen = new Set<string>();
function acceptJob(job: ImportJob) {
  selectedJobId = job.id;
  currentJob.value = job;
  jobs.value = [job, ...jobs.value.filter((j) => j.id !== job.id)];
  if (!isActive(job) && !completionSeen.has(job.id)) {
    completionSeen.add(job.id);
    void loadMaterials();
    emit('changed');
  }
}
async function selectJob(id: string) {
  const epoch = ++jobEpoch,
    context = contextEpoch;
  selectedJobId = id;
  currentJob.value = null;
  controlError.value = '';
  try {
    const result = await stickerMaterials.job(id);
    if (alive && epoch === jobEpoch && context === contextEpoch) {
      // 详情已更新，开始于此响应之前的列表轮询不能倒写该任务。
      jobEpoch++;
      acceptJob(result.job);
    }
  } catch (e) {
    if (alive && epoch === jobEpoch && context === contextEpoch)
      controlError.value = (e as Error).message;
  }
}
watch(selectedAgent, () => {
  contextEpoch++;
  jobEpoch++;
  selectedJobId = '';
  currentJob.value = null;
  controlError.value = '';
  const latest = agentJobs.value[0];
  if (latest) void selectJob(latest.id);
});
async function refreshImport() {
  const context = contextEpoch,
    epoch = jobEpoch;
  try {
    const [agentResult, jobResult] = await Promise.all([
      stickerMaterials.agents(),
      stickerMaterials.jobs(),
    ]);
    if (!alive || context !== contextEpoch || epoch !== jobEpoch) return;
    agents.value = agentResult.agents;
    jobs.value = jobResult.jobs;
    pollError.value = '';
    if (!selectedAgent.value && activeAgents.value[0]) {
      selectedAgent.value = activeAgents.value[0].id;
      return;
    }
    const id = selectedJobId;
    const next = id ? jobs.value.find((j) => j.id === id) : agentJobs.value[0];
    if (next) acceptJob(next);
  } catch (e) {
    if (alive && context === contextEpoch && epoch === jobEpoch)
      pollError.value = (e as Error).message;
  }
}
async function poll() {
  await refreshImport();
  if (alive) pollTimer = setTimeout(poll, 4000);
}
async function createPairing() {
  if (actionBusy.value) return;
  actionBusy.value = true;
  controlError.value = '';
  try {
    const result = await stickerMaterials.pair();
    if (alive) {
      pairing.value = result;
      copyMessage.value = '';
    }
  } catch (e) {
    if (alive) controlError.value = (e as Error).message;
  } finally {
    if (alive) actionBusy.value = false;
  }
}
async function copyPairing() {
  if (!pairing.value || pairingExpired.value) return;
  try {
    await navigator.clipboard.writeText(pairing.value.code);
    if (alive) copyMessage.value = '已复制';
  } catch {
    if (alive) copyMessage.value = '无法自动复制，请手动选中配对码复制';
  }
}
async function startImport() {
  if (actionBusy.value || !online.value || hasActive.value) return;
  const context = contextEpoch,
    id = selectedAgent.value,
    epoch = ++jobEpoch;
  actionBusy.value = true;
  controlError.value = '';
  try {
    const result = await stickerMaterials.start(id);
    if (alive && context === contextEpoch && epoch === jobEpoch) {
      jobEpoch++;
      acceptJob(result.job);
    }
  } catch (e) {
    if (alive && context === contextEpoch && epoch === jobEpoch) controlError.value = (e as Error).message;
  } finally {
    if (alive) actionBusy.value = false;
  }
}
async function cancelImport() {
  if (actionBusy.value || !isActive(currentJob.value)) return;
  const context = contextEpoch,
    id = currentJob.value!.id,
    epoch = ++jobEpoch;
  actionBusy.value = true;
  controlError.value = '';
  try {
    const result = await stickerMaterials.cancel(id);
    if (alive && context === contextEpoch && epoch === jobEpoch) {
      jobEpoch++;
      acceptJob(result.job);
    }
  } catch (e) {
    if (alive && context === contextEpoch && epoch === jobEpoch)
      controlError.value = (e as Error).message;
  } finally {
    if (alive) actionBusy.value = false;
  }
}
async function revokeAgent() {
  if (actionBusy.value || !agent.value || agent.value.revokedAt) return;
  const id = selectedAgent.value,
    context = contextEpoch;
  if (
    !(await confirm('撤销后此助手无法继续导入，需重新配对。已导入的图片不会删除。', {
      title: '撤销本机助手',
      confirmText: '确认撤销',
    })) ||
    !alive ||
    context !== contextEpoch
  )
    return;
  actionBusy.value = true;
  ++jobEpoch;
  try {
    await stickerMaterials.revoke(id);
    if (alive && context === contextEpoch) {
      // 撤销期间已开始的轮询不能把助手恢复成未撤销状态。
      jobEpoch++;
      const found = agents.value.find((a) => a.id === id);
      if (found) found.revokedAt = new Date().toISOString();
      await refreshImport();
    }
  } catch (e) {
    if (alive && context === contextEpoch) controlError.value = (e as Error).message;
  } finally {
    if (alive) actionBusy.value = false;
  }
}
onMounted(() => {
  void loadMaterials(1);
  void poll();
  clockTimer = setInterval(() => {
    now.value = Date.now();
  }, 1000);
});
onBeforeUnmount(() => {
  alive = false;
  materialEpoch++;
  jobEpoch++;
  contextEpoch++;
  clearTimeout(pollTimer);
  clearInterval(clockTimer);
});
</script>

<template>
  <section class="content-library materials-page" aria-label="表情素材库">
    <section class="library-panel import-panel" aria-label="批量上传原图">
      <h3>批量上传表情原图</h3>
      <p>先用本地独立导出工具把微信收藏存入文件夹，再在这里多选上传，无需配对或保持助手运行。按原图 SHA256 比对当前后台，已有图片保留全部推荐词；新图先未分配，添加推荐词后进入关键词推荐图。</p>
      <p>每批最多 1000 张，单张不超过 10 MB；保留 GIF 动画。仅完全相同的原图去重，压缩或重新编码后的图片可能被识别为新图。</p>
      <input ref="batchInput" type="file" hidden accept="image/gif,image/png,image/jpeg,image/webp" multiple aria-label="选择批量上传图片" :disabled="batchBusy || keywordBusy" @change="chooseBatch" />
      <div class="library-actions">
        <button class="library-button primary" :disabled="batchBusy || keywordBusy" @click="batchInput?.click()">选择图片批量上传</button>
        <button v-if="batchBusy" class="library-button" @click="cancelBatch" :disabled="batchCancelled">停止后续上传</button>
        <button v-if="!batchBusy && batchRetryable" class="library-button" :disabled="keywordBusy" @click="runBatch">重试失败或未完成项</button>
        <span v-if="batchRows.length" role="status">已处理 {{ batchFinished }} / {{ batchRows.length }} 张{{ batchBusy ? '（进行中）' : '（本批结束）' }}</span>
      </div>
      <p v-if="batchBusy">离开页面将停止后续上传；当前请求可能已保存，重新上传时会再次去重。</p>
      <p v-if="batchError" role="alert" class="library-notice error">{{ batchError }}</p>
      <details v-if="batchRows.length" open class="batch-results">
        <summary>本批处理明细（已有推荐词自动显示）</summary>
        <ul><li v-for="(row, index) in batchRows" :key="index">
          <strong>{{ row.file.name }}</strong> · {{ batchLabels[row.status] }}
          <template v-if="row.material">
            <span v-for="keyword in row.material.keywords" :key="keyword" class="library-badge">{{ keyword }}</span>
            <span v-if="!row.material.keywords.length" class="library-badge">未分配推荐词</span>
          </template>
          <span v-if="row.error" class="batch-error">{{ row.error }}</span>
        </li></ul>
      </details>
    </section>
    <details class="library-panel import-panel">
      <summary>可选：本机助手配对后从电脑微信直接导入</summary>
      <h3>从电脑微信导入收藏表情</h3>
      <p>
        由本机助手读取收藏原图，再上传到当前后台；无需连接手机。相同原图自动跳过，保留已有关键词。新图先进入未分配素材，不参与手机推荐。
      </p>
      <details>
        <summary>首次使用：安装与配对说明</summary>
        <p>
          需要 Windows 与 Python 3.12。从项目源码目录 tools/wechat-sticker-import
          获取脚本，在该目录的 PowerShell 中安装（把域名和路径换为本机实际值）：
        </p>
        <pre>
./install.ps1 -Origin 'https://后台域名' -AccountDirectory '…账号目录' -WeixinExe '…Weixin.exe'</pre>
        <p>安装后执行以下命令，输入本页一次性配对码。然后启动并保持助手运行、登录电脑微信：</p>
        <pre>
&amp; "$env:LOCALAPPDATA/shurufa-wechat-import/start.ps1" -Pair
&amp; "$env:LOCALAPPDATA/shurufa-wechat-import/start.ps1"</pre>
        <p>
          助手主动通过 HTTPS
          连接后台；浏览器按钮不会直接读取微信或自动安装软件。请只在自己的电脑配对。
        </p>
      </details>
      <div class="library-actions">
        <button class="library-button" :disabled="actionBusy" @click="createPairing">
          生成一次性配对码
        </button>
        <label
          >本机助手
          <select v-model="selectedAgent" class="library-input" :disabled="actionBusy">
            <option value="" disabled>请选择助手</option>
            <option v-for="a in activeAgents" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select></label
        >
        <span v-if="agent" class="library-badge">{{
          agent.revokedAt ? '已撤销' : online ? '在线' : '离线'
        }}</span>
        <button
          class="library-button primary"
          :disabled="actionBusy || !online || hasActive"
          @click="startImport"
        >
          一键导入微信收藏
        </button>
        <button
          v-if="agent && !agent.revokedAt"
          class="text-button danger"
          :disabled="actionBusy"
          @click="revokeAgent"
        >
          撤销助手
        </button>
      </div>
      <p v-if="!online">请先安装、配对并启动本机助手，保持运行；在线状态依据最近 90 秒的心跳。</p>
      <div v-if="pairing" class="library-notice">
        <template v-if="pairingExpired">配对码已过期，请重新生成。</template
        ><template v-else
          >一次性配对码：<code class="pair-code">{{ pairing.code }}</code
          >（{{ new Date(pairing.expiresAt).toLocaleTimeString() }} 到期，使用后失效）<button
            class="text-button"
            @click="copyPairing"
          >
            复制
          </button></template
        ><span>{{ copyMessage }}</span>
      </div>
      <p v-if="controlError" class="library-notice error" role="alert">{{ controlError }}</p>
      <p v-if="pollError" class="library-notice error" role="alert">
        状态刷新失败：{{ pollError }}；正在自动重试，当前显示可能不是最新状态。
      </p>
      <label v-if="agentJobs.length"
        >最近任务
        <select
          class="library-input"
          :value="currentJob?.id || ''"
          :disabled="actionBusy"
          @change="selectJob(($event.target as HTMLSelectElement).value)"
        >
          <option value="" disabled>读取任务中…</option>
          <option v-for="j in agentJobs" :key="j.id" :value="j.id">
            {{ new Date(j.createdAt).toLocaleString() }} · {{ statusText(j.status) }}
          </option>
        </select></label
      >
      <div v-if="currentJob" class="job-progress" role="status">
        <h4>
          {{ statusText(currentJob.status)
          }}<span v-if="currentJob.status === 'completed' && partialFailure">（部分项目失败）</span>
        </h4>
        <p v-if="currentJob.status === 'running' && !currentJob.discovered">
          正在本机采集与校验，暂未收到数量；不代表 0 张。
        </p>
        <div class="import-counts">
          <span>发现 {{ currentJob.discovered }}</span
          ><span>有效 {{ currentJob.validated }}</span
          ><span>校验失败 {{ currentJob.validationFailed }}</span
          ><span>已有跳过 {{ currentJob.counts.existing }}</span
          ><span>新增 {{ currentJob.counts.imported }}</span
          ><span>上传失败 {{ currentJob.counts.failed }}</span
          ><span>待传 {{ currentJob.counts.missing }}</span>
        </div>
        <p v-if="currentJob.jobErrorCode">{{ errorText(currentJob.jobErrorCode) }}</p>
        <p v-for="(count, code) in currentJob.sourceErrors" :key="code">
          {{ errorText(String(code)) }}：{{ count }}
        </p>
        <p v-for="item in currentJob.errors" :key="item.code">
          {{ errorText(item.code) }}：{{ item.count }}
        </p>
        <p v-if="['cancelled', 'expired', 'failed'].includes(currentJob.status)">
          本次任务已停止，已成功导入的图片仍保留。排查后可重新导入，相同原图会跳过。
        </p>
        <button
          v-if="isActive(currentJob)"
          class="library-button"
          :disabled="actionBusy"
          @click="cancelImport"
        >
          取消任务（不删除已导入图）
        </button>
      </div>
    </details>
    <form class="material-filters library-actions" @submit.prevent="applyFilter">
      <label
        >分配状态
        <select v-model="filter" class="library-input" @change="applyFilter">
          <option value="all">全部</option>
          <option value="unassigned">未分配</option>
          <option value="assigned">已分配</option>
        </select></label
      >
      <input
        v-model="search"
        class="library-input"
        aria-label="搜索素材关键词"
        placeholder="搜索关键词"
      />
      <button class="library-button" type="submit">搜索</button
      ><button class="text-button" type="button" :disabled="loading" @click="loadMaterials()">
        刷新</button
      ><span>共 {{ total }} 张（按原图去重）</span>
    </form>
    <p>一张图可关联多个关键词；移除关联不是删除图片，移除最后一个关键词后回到未分配。</p>
    <p v-for="warning in warnings" :key="warning" class="library-notice">{{ warning }}</p>
    <p v-if="materialError" role="alert" class="library-notice error">
      素材加载失败：{{ materialError }}
    </p>
    <p v-if="loading" role="status">正在加载素材…</p>
    <p v-else-if="!items.length && !materialError" class="library-empty">
      没有符合条件的素材。可调整筛选，或选择图片批量上传。
    </p>
    <div class="material-grid" :aria-busy="loading">
      <article v-for="m in items" :key="m.sha256" class="library-panel material-card">
        <div class="material-preview">
          <span v-if="failedImages.has(m.sha256)" role="alert">原图加载失败</span
          ><img
            v-else
            :src="previewUrl(m)"
            :alt="m.keywords.join('、') || '未分配表情'"
            loading="lazy"
            @error="failedImages.add(m.sha256)"
          />
        </div>
        <div class="library-actions">
          <span class="library-badge">{{ m.assigned ? '已分配' : '未分配' }}</span
          ><small
            >{{ m.format.toUpperCase() }} ·
            {{ m.width && m.height ? `${m.width} × ${m.height}` : '尺寸未知' }}</small
          >
        </div>
        <small v-if="m.ids.length > 1">汇总 {{ m.ids.length }} 条历史记录，未合并或删除</small>
        <div class="material-keywords">
          <span v-for="keyword in m.keywords" :key="keyword" class="library-badge"
            >{{ keyword }}
            <button
              class="text-button"
              :aria-label="`移除关联：${keyword}`"
              :disabled="batchBusy || cardBusy[m.sha256] || loading"
              @click="changeKeywords(m, [], [keyword])"
            >
              ×
            </button></span
          ><span v-if="!m.keywords.length">暂无关键词</span>
        </div>
        <form @submit.prevent="addKeywords(m)">
          <input
            v-model="drafts[m.sha256]"
            class="library-input"
            aria-label="新增关联关键词"
            placeholder="新增关键词，多个用逗号分隔"
            :disabled="batchBusy || cardBusy[m.sha256]"
          /><button
            class="library-button"
            :disabled="batchBusy || cardBusy[m.sha256] || loading || !drafts[m.sha256]?.trim()"
          >
            {{ cardBusy[m.sha256] ? '保存中…' : '添加关联' }}
          </button>
        </form>
        <p v-if="cardErrors[m.sha256]" role="alert" class="library-notice error">
          {{ cardErrors[m.sha256] }}
        </p>
      </article>
    </div>
    <nav class="library-actions" aria-label="素材分页">
      <button
        class="library-button"
        :disabled="loading || page <= 1"
        @click="loadMaterials(page - 1)"
      >
        上一页</button
      ><span>第 {{ page }} / {{ pageCount }} 页</span
      ><button
        class="library-button"
        :disabled="loading || page >= pageCount"
        @click="loadMaterials(page + 1)"
      >
        下一页
      </button>
    </nav>
  </section>
</template>
<style scoped>
.batch-results ul { max-height: 320px; overflow: auto; padding-left: 20px; }
.batch-results li { padding: 8px 0; overflow-wrap: anywhere; }
.batch-results .library-badge { margin-left: 6px; }
.batch-error { display: block; color: #b42318; }
.materials-page {
  display: grid;
  gap: 18px;
}
.import-panel {
  padding: 20px;
}
.import-panel h3 {
  margin-top: 0;
}
.import-panel p,
.materials-page > p {
  color: #68758a;
  line-height: 1.7;
}
.import-panel details {
  margin: 14px 0;
}
.import-panel pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f3f6fa;
  padding: 12px;
  border-radius: 8px;
}
.library-actions {
  flex-wrap: wrap;
  gap: 10px;
}
.material-filters .library-input {
  width: auto;
  max-width: 100%;
}
.material-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}
.material-card {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.material-preview {
  height: 190px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f6f8fc;
  border-radius: 10px;
}
.material-preview img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
.material-keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.material-card form {
  display: grid;
  gap: 8px;
}
.import-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.import-counts span {
  background: #eef3fa;
  padding: 8px;
  border-radius: 6px;
}
.job-progress {
  border-top: 1px solid #e5eaf1;
  margin-top: 18px;
  padding-top: 8px;
}
.pair-code {
  user-select: all;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 2px;
}
.material-card small {
  color: #68758a;
}
select.library-input {
  width: auto;
  max-width: 100%;
}
</style>
