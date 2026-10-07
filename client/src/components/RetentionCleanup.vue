<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { api, currentUserId, type RetentionDataset, type RetentionFilters } from '../api';
import { useConfirmation } from '../confirmation';

const props = withDefaults(defineProps<{
  dataset: RetentionDataset; label: string; filters?: RetentionFilters; scopeLabel?: string;
  context?: unknown; disabled?: boolean;
}>(), { filters: () => ({}), scopeLabel: '全部记录', disabled: false });
const emit = defineEmits<{ changed: []; busy: [value: boolean] }>();
const busy = ref(false), status = ref(''), error = ref('');
const confirm = useConfirmation();
let alive = true, version = 0;
const context = computed(() => JSON.stringify([currentUserId.value, props.dataset, props.filters, props.context]));
watch(context, () => { version++; status.value = ''; error.value = ''; }, { flush: 'sync' });
onBeforeUnmount(() => { alive = false; version++; });
const note = computed(() => {
  if (props.dataset === 'completions') return '按最后使用时间清理旧候选及对应学习统计，近期候选整条保留；累计次数不能按天拆分，可能减少后台可下发候选，手机既有缓存不承诺同步删除。';
  if (props.dataset === 'input') return '删除共享输入原始记录，会同时影响输入总览、时间线、APP 分布、词云、报告和行为明细；仍有近期操作的完整编辑组会保留。';
  if (props.dataset === 'clipboard') return '仅删除匹配的原始复制、粘贴记录，也会影响其他输入统计和行为明细。';
  if (props.dataset === 'call-recordings') return '同时删除线上音频及其详情；不删除手机本地原件。';
  if (props.dataset === 'navigation') return '同时删除导航记录及对应路线截图。';
  if (props.dataset === 'app-usage') return '按使用段结束时间判断，保留跨越截止时间的完整使用段。';
  return '';
});
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '无';
async function cleanup(days: 1 | 7 | 30) {
  if (busy.value || props.disabled || !currentUserId.value || !alive) return;
  const startedVersion = version, dataset = props.dataset, startedUser = currentUserId.value;
  const filters = { ...props.filters };
  const current = () => alive && version === startedVersion;
  let offset = 0, deleted = 0, skipped = 0, attempted = false;
  busy.value = true; emit('busy', true); status.value = '正在预览旧记录…'; error.value = '';
  try {
    const preview = await api.previewStatisticsCleanup(dataset, { days, filters });
    if (!current()) return;
    if (!preview.total_records) { status.value = '当前范围没有需要清理的旧记录。'; return; }
    status.value = `预览：${preview.total_records} 条旧记录。`;
    const accepted = await confirm(`清理当前手机的${props.label}，保留最近${days}天（每一天按24小时计算）。\n范围：${props.scopeLabel}；跨全部分页，不受查看日期限制。\n删除严格早于 ${time(preview.cutoff)}（北京时间）的 ${preview.total_records} 条记录${preview.total_files ? `，包含 ${preview.total_files} 个文件` : ''}。\n记录时间：${time(preview.first_at)} 至 ${time(preview.last_at)}。\n${note.value}\n预览后新增的记录不会加入；变化的记录会跳过。确认后自动分批处理，请保持本页面打开。删除不可撤销。`, { title: '确认清理旧记录', confirmText: '确认清理' });
    if (!accepted || !current()) { if (current()) status.value = '已取消清理。'; return; }
    while (current()) {
      attempted = true;
      const progress = await api.deleteStatisticsCleanupBatch(dataset, { confirm: 'DELETE', token: preview.token, offset });
      if (!current()) return;
      deleted = progress.deleted_records; skipped = progress.skipped_records;
      status.value = `已处理 ${progress.processed} / ${progress.total} 条，删除 ${deleted} 条，跳过 ${skipped} 条。`;
      if (progress.done) {
        status.value = `清理完成：删除 ${deleted} 条，跳过 ${skipped} 条。${progress.files_pending ? '部分文件等待后台释放。' : ''}`;
        break;
      }
      if (progress.processed <= offset) throw new Error('清理进度未推进，请重新预览后重试。');
      offset = progress.processed;
    }
  } catch (e) {
    if (current()) {
      status.value = attempted ? `清理已停止：已确认删除 ${deleted} 条，跳过 ${skipped} 条。最后一次请求的结果可能尚未收到，请刷新后重新预览。` : '';
      error.value = e instanceof Error ? e.message : '清理失败，请重新预览后重试。';
    }
  } finally {
    if (attempted && alive && currentUserId.value === startedUser && props.dataset === dataset) emit('changed');
    busy.value = false;
    if (alive) emit('busy', false);
  }
}
</script>
<template>
  <section class="retention-cleanup" :aria-label="`${label}旧记录清理`">
    <div class="retention-actions"><strong>{{ label }}清理</strong><button v-for="days in ([1, 7, 30] as const)" :key="days" type="button" :data-testid="`retention-keep-${days}`" :disabled="busy || disabled || !currentUserId" @click="cleanup(days)">保留最近{{ days }}天</button><span v-if="busy" role="status">处理中…</span></div>
    <p>范围：当前手机 · {{ scopeLabel }}。清理跨全部分页，不受查看日期限制；点击后先预览。{{ note }}</p>
    <p v-if="status" role="status">{{ status }}</p><p v-if="error" role="alert" class="retention-error">{{ error }}</p>
  </section>
</template>
<style scoped>
.retention-cleanup{margin:16px 0;padding:12px 14px;border:1px solid #e2e8f0;border-radius:8px;background:#f8fafc;color:#334155}.retention-actions{display:flex;flex-wrap:wrap;align-items:center;gap:8px}.retention-actions strong{margin-right:6px}.retention-actions button{padding:7px 10px;border:1px solid #cbd5e1;border-radius:5px;background:white;color:#9f352c;cursor:pointer}.retention-actions button:disabled{opacity:.5;cursor:default}.retention-cleanup p{margin:8px 0 0;font-size:12px;line-height:1.6;overflow-wrap:anywhere}.retention-error{color:#b42318}
</style>
