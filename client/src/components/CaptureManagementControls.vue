<script setup lang="ts">
import { ref,watch,nextTick } from 'vue';
import type { CaptureManagement } from '../captureManagement';
const props=defineProps<{ manager:Pick<CaptureManagement,keyof CaptureManagement>; enabled:boolean; rowCount:number }>();
const emit=defineEmits<{ refresh:[] }>();
const panel=ref<HTMLElement>();
watch(()=>[props.manager.editor,props.manager.pendingDelete.length,props.manager.cleanup],async()=>{if(props.manager.editor||props.manager.pendingDelete.length||props.manager.cleanup){await nextTick();panel.value?.scrollIntoView({block:'nearest'});}});
const days=ref<1|7|30>(7);
watch(days,()=>{props.manager.cleanup=null;});
const time=(value:string)=>new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
async function complete(action:Promise<boolean>){if(await action)emit('refresh');}
</script>
<template>
 <div ref="panel" class="capture-management">
  <div class="actions">
   <button :disabled="!enabled||manager.busy||!rowCount" @click="manager.selectAll()">全选当前页</button>
   <button :disabled="manager.busy||!manager.selectedIds.length" @click="manager.cancelSelection()">取消选择</button>
   <button :disabled="!enabled||manager.busy||!manager.selectedIds.length" @click="manager.requestDelete()">删除所选（{{ manager.selectedIds.length }}）</button>
   <label>旧记录清理 <select v-model="days" aria-label="保留天数" :disabled="manager.busy"><option :value="1">保留 1 天</option><option :value="7">保留 7 天</option><option :value="30">保留 30 天</option></select></label>
   <button :disabled="!enabled||manager.busy" @click="manager.previewCleanup(days)">预览清理</button>
  </div>
  <p v-if="manager.error" role="alert" class="error">{{ manager.error }}</p>
  <p v-if="manager.message" role="status">{{ manager.message }}</p>
  <p v-if="manager.busy" role="status">正在处理…</p>
  <div v-if="manager.pendingDelete.length" class="confirmation" role="region" aria-label="确认删除记录">
   <p>确定删除当前手机已选择的 {{ manager.pendingDelete.length }} 条记录？删除后无法恢复。</p>
   <button :disabled="manager.busy" @click="complete(manager.confirmDelete())">确认删除</button>
   <button :disabled="manager.busy" @click="manager.pendingDelete=[]">取消删除</button>
  </div>
  <div v-if="manager.cleanup" class="confirmation" role="region" aria-label="清理预览">
   <p>当前手机及筛选范围内，{{ time(manager.cleanup.cutoff) }} 之前共 {{ manager.cleanup.total }} 条旧记录。</p>
   <button :disabled="manager.busy||!manager.cleanup.total" @click="complete(manager.confirmCleanup())">{{ manager.cleanup.offset?'继续清理':'确认清理' }}</button>
   <button v-if="manager.busy" @click="manager.stopCleanup()">停止后续清理</button>
   <button v-else @click="manager.cleanup=null">取消清理</button>
  </div>
  <form v-if="manager.editor" class="editor" aria-label="编辑名称备注" @submit.prevent="complete(manager.saveEdit())">
   <label>名称 <input v-model="manager.editor.title" maxlength="200" aria-label="记录名称" :disabled="manager.busy"></label>
   <label>备注 <textarea v-model="manager.editor.note" maxlength="2000" aria-label="记录备注" :disabled="manager.busy"></textarea></label>
   <button :disabled="manager.busy">保存名称备注</button>
   <button type="button" :disabled="manager.busy" @click="manager.editor=null">取消编辑</button>
  </form>
 </div>
</template>
<style scoped>
.capture-management{margin:16px 0}.actions{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.actions label{display:flex;align-items:center;gap:6px}button,select,input,textarea{font:inherit;border:1px solid #cbd5e1;border-radius:6px;padding:8px 10px;background:white;color:inherit;max-width:100%}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}.confirmation,.editor{border:1px solid #d4dce8;border-radius:8px;padding:14px;margin-top:12px;background:#f8fafc}.confirmation p{margin-bottom:10px;line-height:1.6}.confirmation button,.editor button{margin-right:8px}.editor label{display:flex;align-items:flex-start;gap:8px;margin-bottom:12px}.editor input,.editor textarea{width:min(100%,500px)}.editor textarea{min-height:70px}.error{color:#b42318}p[role]{margin:10px 0;line-height:1.6}
</style>
