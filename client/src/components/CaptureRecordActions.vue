<script setup lang="ts">
import type { CaptureManagement,ManagedCapture } from '../captureManagement';
defineProps<{manager:Pick<CaptureManagement,keyof CaptureManagement>;row:ManagedCapture}>();
</script>
<template>
 <div class="record-actions">
  <label><input type="checkbox" :aria-label="`选择记录 ${row.id}`" :checked="manager.selectedIds.includes(row.id)" :disabled="manager.busy" @change="manager.toggle(row.id)"> 选择</label>
  <button :disabled="manager.busy" @click="manager.edit(row)">编辑名称备注</button>
  <button :disabled="manager.busy" @click="manager.requestDelete(row.id)">删除记录</button>
 </div>
</template>
<style scoped>
.record-actions{display:flex;align-items:center;flex-wrap:wrap;gap:10px;padding:12px 16px;border-top:1px solid #e2e8f0;font-size:12px}.record-actions label{display:flex;align-items:center;gap:4px}button{border:1px solid #cbd5e1;border-radius:5px;padding:6px 8px;background:white;color:inherit;cursor:pointer}button:disabled{opacity:.5;cursor:default}
</style>
